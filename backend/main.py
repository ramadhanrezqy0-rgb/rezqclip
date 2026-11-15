"""AutoClip AI backend v0.4: validated uploads, AI clipping, subtitles and revision."""
from pathlib import Path
import json, os, shutil, subprocess, uuid
from fastapi import FastAPI, UploadFile, File, Form, HTTPException
from fastapi.staticfiles import StaticFiles

BASE = Path(__file__).parent
UPLOADS, OUTPUTS, JOBS = BASE / "uploads", BASE / "outputs", BASE / "jobs"
MAX_UPLOAD_BYTES = int(os.getenv("MAX_UPLOAD_MB", "300")) * 1024 * 1024
for folder in (UPLOADS, OUTPUTS, JOBS):
    folder.mkdir(parents=True, exist_ok=True)

app = FastAPI(title="AutoClip AI API", version="0.5.0")
app.mount("/clips", StaticFiles(directory=OUTPUTS), name="clips")
app.mount("/subtitles", StaticFiles(directory=OUTPUTS), name="subtitles")


def run(cmd):
    p = subprocess.run(cmd, capture_output=True, text=True)
    if p.returncode:
        raise RuntimeError((p.stderr or "FFmpeg gagal")[-2000:])


def probe_video(path):
    p = subprocess.run([
        "ffprobe", "-v", "error", "-show_entries",
        "format=duration:stream=codec_type", "-of", "json", str(path)
    ], capture_output=True, text=True)
    if p.returncode:
        raise RuntimeError("File bukan video yang valid atau codec tidak didukung.")
    try:
        info = json.loads(p.stdout)
        total = float(info["format"]["duration"])
        has_video = any(stream.get("codec_type") == "video" for stream in info.get("streams", []))
    except (ValueError, KeyError, TypeError):
        raise RuntimeError("Metadata video tidak lengkap atau rusak.")
    if not has_video or total <= 0:
        raise RuntimeError("File tidak memiliki stream video yang valid.")
    return total


def duration(path):
    return probe_video(path)


def openai_client():
    try:
        from openai import OpenAI
    except ImportError as exc:
        raise RuntimeError("Paket openai belum terpasang. Jalankan pip install -r requirements.txt.") from exc
    return OpenAI()


def transcribe(source):
    audio = UPLOADS / f"{source.stem}.mp3"
    try:
        run(["ffmpeg", "-y", "-i", str(source), "-vn", "-ac", "1", "-ar", "16000", "-b:a", "64k", str(audio)])
        with audio.open("rb") as f:
            result = openai_client().audio.transcriptions.create(
                model=os.getenv("OPENAI_TRANSCRIPTION_MODEL", "whisper-1"),
                file=f, response_format="verbose_json", timestamp_granularities=["segment"])
        segs = [{"start": float(s.start), "end": float(s.end), "text": str(s.text).strip()}
                for s in (getattr(result, "segments", None) or [])]
        return segs, getattr(result, "text", "") or ""
    finally:
        audio.unlink(missing_ok=True)


def stamp(t):
    ms = int(max(0, t) * 1000)
    h, ms = divmod(ms, 3600000)
    m, ms = divmod(ms, 60000)
    s, ms = divmod(ms, 1000)
    return f"{h:02}:{m:02}:{s:02},{ms:03}"


def write_srt(segments, start, end, path):
    lines, n = [], 1
    for seg in segments:
        a, b = max(start, seg["start"]), min(end, seg["end"])
        txt = seg["text"].strip()
        if b <= a or not txt:
            continue
        lines += [str(n), f"{stamp(a-start)} --> {stamp(b-start)}", txt, ""]
        n += 1
    path.write_text("\n".join(lines), encoding="utf-8")
    return n - 1


def choose_ranges(transcript_segments, instruction, total, count, max_len):
    transcript = "\n".join(f'[{s["start"]:.1f}-{s["end"]:.1f}] {s["text"]}' for s in transcript_segments)
    response = openai_client().chat.completions.create(
        model=os.getenv("OPENAI_MODEL", "gpt-4o-mini"), temperature=0.2,
        response_format={"type": "json_object"},
        messages=[
            {"role": "system", "content": (
                "You are a careful short-video editor. Choose distinct timestamp ranges from "
                "the transcript that best match the user's instruction. Preserve context, setup "
                "and punchlines. Do not invent content. Return JSON {clips:[{start,end,title,reason}]}. "
                "Each range must be 8 seconds or longer, at most the requested max duration, "
                "inside the source duration, and avoid substantial overlap.")},
            {"role": "user", "content": (
                f"Instruction: {instruction}\nVideo duration: {total}\nClip count: {count}\n"
                f"Maximum seconds per clip: {max_len}\nTranscript:\n{transcript[:50000]}")}
        ])
    data = json.loads(response.choices[0].message.content or "{}")
    chosen = []
    for c in data.get("clips", [])[:count]:
        try:
            a, b = max(0, float(c["start"])), min(total, float(c["end"]))
        except (KeyError, TypeError, ValueError):
            continue
        if 8 <= b-a <= max_len:
            chosen.append({"start": a, "end": b, "title": str(c.get("title", "Clip AI"))[:80],
                           "reason": str(c.get("reason", ""))[:250]})
    if not chosen:
        raise RuntimeError("AI tidak menemukan bagian sesuai instruksi. Coba perintah lain.")
    return chosen


def render(source, job, index, start, end, segments, burn_subtitles=True):
    name = f"{job}_clip_{index}.mp4"
    out = OUTPUTS / name
    srt = OUTPUTS / f"{job}_clip_{index}.srt"
    write_srt(segments, start, end, srt)
    vf = "scale=720:1280:force_original_aspect_ratio=increase,crop=720:1280"
    if burn_subtitles and srt.exists() and srt.stat().st_size:
        escaped = str(srt).replace("\\", "/").replace(":", r"\:").replace("'", r"\'")
        vf += (f",subtitles='{escaped}':force_style='FontName=Arial,FontSize=18,"
               "PrimaryColour=&H00FFFFFF,OutlineColour=&H00000000,BorderStyle=1,"
               "Outline=2,Shadow=1,Alignment=2,MarginV=80'")
    run(["ffmpeg", "-y", "-ss", str(start), "-i", str(source), "-t", str(end-start),
         "-vf", vf, "-c:v", "libx264", "-preset", "veryfast", "-crf", "23",
         "-c:a", "aac", "-b:a", "128k", "-movflags", "+faststart", str(out)])
    return {"name": f"Clip {index}", "url": f"/clips/{name}",
            "subtitle_url": f"/subtitles/{srt.name}", "start": round(start, 1), "end": round(end, 1)}


@app.get("/")
def health():
    return {"status": "ok", "version": "0.5.0", "ai_ready": bool(os.getenv("OPENAI_API_KEY")),
            "ffmpeg_ready": shutil.which("ffmpeg") is not None,
            "message": "AI siap." if os.getenv("OPENAI_API_KEY") else "OPENAI_API_KEY belum diatur di server."}


@app.post("/process")
async def process(file: UploadFile = File(...), instruction: str = Form("Cari bagian paling menarik."),
                  clip_count: int = Form(3), max_duration: int = Form(45)):
    if not file.content_type or not file.content_type.startswith("video/"):
        raise HTTPException(400, "Unggah file video.")
    if not instruction.strip():
        raise HTTPException(400, "Instruksi tidak boleh kosong.")
    if not 1 <= clip_count <= 10:
        raise HTTPException(400, "Jumlah clip harus 1–10.")
    if not 8 <= max_duration <= 180:
        raise HTTPException(400, "Durasi harus 8–180 detik.")
    if not os.getenv("OPENAI_API_KEY"):
        raise HTTPException(503, "AI belum dikonfigurasi. Atur OPENAI_API_KEY di server terlebih dahulu.")

    suffix = Path(file.filename or "video.mp4").suffix.lower()
    if suffix not in {".mp4", ".mov", ".m4v", ".webm", ".mkv", ".avi"}:
        raise HTTPException(415, "Format video harus MP4, MOV, M4V, WEBM, MKV, atau AVI.")
    job = uuid.uuid4().hex
    source = UPLOADS / f"{job}_source{suffix}"
    size = 0
    try:
        with source.open("wb") as f:
            while chunk := await file.read(1024 * 1024):
                size += len(chunk)
                if size > MAX_UPLOAD_BYTES:
                    raise HTTPException(413, f"Ukuran video melewati batas {MAX_UPLOAD_BYTES // (1024*1024)} MB.")
                f.write(chunk)
        if size == 0:
            raise HTTPException(400, "File video kosong.")
        try:
            total = duration(source)
        except RuntimeError as exc:
            raise HTTPException(415, str(exc)) from exc
        if total < 8:
            raise HTTPException(400, "Video minimal 8 detik.")
        transcript_segments, _ = transcribe(source)
        if not transcript_segments:
            raise HTTPException(422, "Transkrip kosong; pastikan video memiliki audio yang jelas.")
        ranges = choose_ranges(transcript_segments, instruction, total, clip_count, max_duration)
        meta = {"source": str(source), "instruction": instruction, "duration": total,
                "transcript": transcript_segments, "ranges": ranges, "clips": []}
        results = []
        for i, r in enumerate(ranges, 1):
            item = render(source, job, i, r["start"], r["end"], transcript_segments, True)
            item.update({"name": r["title"], "reason": r["reason"]})
            results.append(item)
        meta["clips"] = results
        (JOBS / f"{job}.json").write_text(json.dumps(meta, ensure_ascii=False), encoding="utf-8")
        return {"job_id": job, "ai_used": True, "mode": "ai", "warning": None,
                "instruction": instruction, "source_duration": total, "clips": results}
    except HTTPException:
        source.unlink(missing_ok=True)
        raise
    except Exception as e:
        source.unlink(missing_ok=True)
        for artifact in OUTPUTS.glob(f"{job}_clip_*"):
            artifact.unlink(missing_ok=True)
        (JOBS / f"{job}.json").unlink(missing_ok=True)
        raise HTTPException(500, str(e)[:500])
    finally:
        await file.close()


@app.post("/revise/{job_id}/{clip_index}")
async def revise(job_id: str, clip_index: int, start: float = Form(...), end: float = Form(...),
                 subtitle_text: str = Form(""), burn_subtitles: bool = Form(True)):
    if not job_id.isalnum() or not 1 <= clip_index <= 10:
        raise HTTPException(400, "ID sesi atau nomor clip tidak valid.")
    meta_path = JOBS / f"{job_id}.json"
    if not meta_path.exists():
        raise HTTPException(404, "Sesi edit tidak ditemukan.")
    meta = json.loads(meta_path.read_text(encoding="utf-8"))
    source = Path(meta["source"]).resolve()
    if source.parent != UPLOADS.resolve() or not source.exists():
        raise HTTPException(410, "Video sumber sudah tidak tersedia.")
    total = duration(source)
    if start < 0 or end > total or end-start < 3 or end <= start:
        raise HTTPException(400, "Rentang waktu revisi tidak valid.")
    segments = meta.get("transcript", [])
    if subtitle_text.strip():
        segments = [{"start": start, "end": end, "text": subtitle_text.strip()}]
    try:
        result = render(source, job_id, clip_index, start, end, segments, burn_subtitles)
    except Exception as e:
        raise HTTPException(500, str(e)[:500])
    result["name"] = f"Clip {clip_index} (revisi)"
    result["reason"] = "Rentang waktu/subtitle diperbarui"
    return result
