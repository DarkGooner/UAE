import os, gc, re, hashlib, threading, time
from concurrent.futures import ThreadPoolExecutor, as_completed
try:
    import UnityPy
except Exception:
    UnityPy = None

_lock = threading.Lock(); _pause = threading.Event(); _stop = False
_status = {"done":0,"total":0,"images":0,"videos":0,"message":"Ready","speed":"0 files/s"}

def _set(**kw):
    with _lock: _status.update(kw)

def get_status():
    with _lock: s = dict(_status)
    return f"{s['done']}|{s['total']}|{s['images']}|{s['videos']}|{s['message']}|{s['speed']}|{1 if _stop else 0}"

def pause(): _pause.set()
def resume(): _pause.clear()

def _safe(s, fallback):
    s = str(s or fallback); s = "".join(c if c.isalnum() or c in "._- " else "_" for c in s)
    return s[:100].strip() or fallback

def _output_dir(base, kind, source, game_root, preserve):
    if not preserve: return os.path.join(base, "extracted_art", kind)
    rel = os.path.relpath(os.path.dirname(source), game_root)
    return os.path.join(base, "extracted_art", kind, rel if rel != "." else "root")

def _process(path, game_root, output_root, want_images, want_videos, skip_existing, preserve):
    ni = nv = 0
    try:
        env = UnityPy.load(path)
        rel_hash = hashlib.sha1(os.path.relpath(path, game_root).encode("utf-8", "ignore")).hexdigest()[:10]
        for obj in env.objects:
            while _pause.is_set() and not _stop: time.sleep(0.25)
            if _stop: break
            try:
                typ = obj.type.name
                if want_images and typ in ("Texture2D", "Sprite"):
                    data = obj.read(); img = getattr(data, "image", None)
                    if img is None: continue
                    name = _safe(getattr(data, "name", ""), typ)
                    dest_dir = _output_dir(output_root, "images", path, game_root, preserve); os.makedirs(dest_dir, exist_ok=True)
                    dest = os.path.join(dest_dir, f"{name}_{rel_hash}_{obj.path_id}.png")
                    if (not skip_existing) or (not os.path.exists(dest)):
                        img.save(dest, "PNG"); ni += 1
                    try: img.close()
                    except Exception: pass
                    del img, data
                elif want_videos and typ == "VideoClip":
                    data = obj.read(); b = getattr(data, "m_VideoData", None)
                    if b:
                        name = _safe(getattr(data, "name", ""), "Video")
                        dest_dir = _output_dir(output_root, "videos", path, game_root, preserve); os.makedirs(dest_dir, exist_ok=True)
                        dest = os.path.join(dest_dir, f"{name}_{rel_hash}_{obj.path_id}.mp4")
                        if (not skip_existing) or (not os.path.exists(dest)):
                            with open(dest, "wb") as f: f.write(b)
                            nv += 1
                    del data
            except Exception: continue
        del env; gc.collect()
    except Exception: pass
    return ni, nv

def _find_assets(game_root):
    found = []; seen = set()
    for root, dirs, names in os.walk(game_root):
        for f in names:
            low = f.lower()
            if low.endswith((".assets", ".bundle", ".unity3d")) or low.startswith("level"):
                p = os.path.join(root, f)
                if p not in seen: seen.add(p); found.append(p)
    return found

def start_extraction(game_root, output_root, cache_root=None, workers=2, want_images=True, want_videos=True, skip_existing=True, preserve=False):
    global _stop
    _stop = False; _pause.clear()
    if UnityPy is None: _set(message="UnityPy failed to load"); return
    game_root = os.path.abspath(game_root); output_root = os.path.abspath(output_root)
    os.makedirs(os.path.join(output_root, "extracted_art"), exist_ok=True)
    _set(done=0,total=0,images=0,videos=0,message="Scanning Unity files...",speed="0 files/s")
    files = _find_assets(game_root); _set(total=len(files), message="Extracting..." if files else "No Unity asset files found")
    if not files: return
    started = time.time()
    with ThreadPoolExecutor(max_workers=max(1, min(int(workers), 4))) as ex:
        futures = {ex.submit(_process, p, game_root, output_root, want_images, want_videos, skip_existing, preserve): p for p in files}
        for fut in as_completed(futures):
            if cache_root and os.path.exists(os.path.join(cache_root, "unity_extractor_stop")): _stop = True
            if _stop:
                for f in futures: f.cancel()
                _set(message="Stopped", speed=""); return
            try: ni, nv = fut.result()
            except Exception: ni = nv = 0
            with _lock:
                _status["done"] += 1; _status["images"] += ni; _status["videos"] += nv
                elapsed = max(time.time()-started, 0.001); _status["speed"] = f"{_status['done']/elapsed:.1f} files/s"
                _status["message"] = "Paused" if _pause.is_set() else "Extracting..."
    _set(message="Finished", speed=_status.get("speed",""))
