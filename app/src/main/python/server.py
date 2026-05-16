import threading
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from ytmusicapi import YTMusic
import yt_dlp

app = FastAPI()
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])
ytmusic = YTMusic()

@app.get("/api/search")
def search(q: str):
    artists_raw = ytmusic.search(q, filter='artists', limit=12)
    songs_raw = ytmusic.search(q, filter='songs', limit=12)
    albums_raw = ytmusic.search(q, filter='albums', limit=12)
    artists = [{"id": a.get('browseId'), "name": a.get('artist'), "thumbnails": a.get('thumbnails', [])} for a in artists_raw if a.get('browseId')]
    songs = [{"id": s.get('videoId'), "title": s.get('title'), "artist": (s.get('artists') or [{}])[0].get('name',''), "artists": [{"id": a.get('id'), "name": a.get('name')} for a in s.get('artists',[])], "thumbnails": s.get('thumbnails', []), "duration": s.get('duration')} for s in songs_raw if s.get('videoId')]
    albums = [{"id": al.get('browseId'), "title": al.get('title'), "artist": (al.get('artists') or [{}])[0].get('name',''), "year": al.get('year'), "thumbnails": al.get('thumbnails', [])} for al in albums_raw if al.get('browseId')]
    return {"artists": artists, "songs": songs, "albums": albums}

@app.get("/api/artist/{bid}")
def artist(bid: str):
    data = ytmusic.get_artist(bid)
    return data

@app.get("/api/album/{bid}")
def album(bid: str):
    data = ytmusic.get_album(bid)
    tracks = [{"title": t.get('title'), "duration": t.get('duration'), "videoId": t.get('videoId'), "artists": [{"id": a.get('id'), "name": a.get('name')} for a in t.get('artists',[])]} for t in data.get('tracks',[])]
    data['tracks'] = tracks
    return data

@app.get("/api/stream")
def stream(videoId: str = None, query: str = None):
    opts = {'format':'bestaudio[ext=m4a]/bestaudio/best','quiet':True,'no_warnings':True,'skip_download':True,'nocheckcertificate':True}
    url = f"https://www.youtube.com/watch?v={videoId}" if videoId else f"ytsearch1:{query}"
    with yt_dlp.YoutubeDL(opts) as ydl:
        info = ydl.extract_info(url, download=False)
        if 'entries' in info: info = info['entries'][0]
        return {"url": info.get('url')}
def start_server():
    import uvicorn
    def run():
        uvicorn.run(app, host="127.0.0.1", port=8000, log_level="error")
    t = threading.Thread(target=run, daemon=True)
    t.start()
