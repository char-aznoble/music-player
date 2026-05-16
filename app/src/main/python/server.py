import threading
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import StreamingResponse
import yt_dlp
import requests
from ytmusicapi import YTMusic

app = FastAPI()
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])
ytmusic = YTMusic()

# --- your existing search/artist/album endpoints unchanged ---
@app.get("/api/search")
def search(q: str):
    try:
        artists_raw = ytmusic.search(q, filter='artists', limit=12)
        songs_raw = ytmusic.search(q, filter='songs', limit=12)
        albums_raw = ytmusic.search(q, filter='albums', limit=12)
        artists = []
        for a in artists_raw:
            bid = a.get('browseId') or a.get('channelId') or a.get('id')
            if bid:
                name = a.get('artist') or a.get('name') or a.get('title')
                if name:
                    artists.append({"id": bid, "browseId": bid, "name": name, "thumbnails": a.get('thumbnails', [])})
        songs = []
        for s in songs_raw:
            vid = s.get('videoId')
            if vid:
                title = s.get('title')
                raw_artists = s.get('artists') or []
                artist_name = raw_artists[0].get('name') if raw_artists else (s.get('artist') or '')
                songs.append({
                    "id": vid, "videoId": vid, "title": title, "artist": artist_name,
                    "artists": [{"id": art.get('id') or art.get('browseId'), "name": art.get('name')} for art in raw_artists],
                    "thumbnails": s.get('thumbnails', []), "duration": s.get('duration')
                })
        albums = []
        for al in albums_raw:
            bid = al.get('browseId') or al.get('id')
            if bid:
                title = al.get('title') or al.get('name')
                raw_artists = al.get('artists') or []
                artist_name = raw_artists[0].get('name') if raw_artists else (al.get('artist') or '')
                albums.append({
                    "id": bid, "browseId": bid, "title": title, "artist": artist_name,
                    "artists": [{"id": art.get('id') or art.get('browseId'), "name": art.get('name')} for art in raw_artists],
                    "year": al.get('year'), "thumbnails": al.get('thumbnails', [])
                })
        return {"artists": artists, "songs": songs, "albums": albums}
    except Exception as e:
        return {"error": str(e), "artists": [], "songs": [], "albums": []}

@app.get("/api/artist/{bid}")
def artist(bid: str):
    # ... keep your existing implementation ...
    try:
        data = ytmusic.get_artist(bid)
        def get_results(key):
            category = data.get(key, {})
            return category if isinstance(category, list) else category.get('results', [])
        songs = [{"videoId": s.get('videoId'), "title": s.get('title'), "thumbnails": s.get('thumbnails', []), "duration": s.get('duration'), "artists": [{"id": a.get('id'), "name": a.get('name')} for a in s.get('artists', [])]} for s in get_results('songs')]
        albums = [{"id": a.get('browseId'), "title": a.get('title'), "thumbnails": a.get('thumbnails', []), "year": a.get('year'), "type": "Album"} for a in get_results('albums')]
        singles = [{"id": s.get('browseId'), "title": s.get('title'), "thumbnails": s.get('thumbnails', []), "year": s.get('year'), "type": "Single"} for s in get_results('singles')]
        return {"name": data.get('name'), "description": data.get('description'), "thumbnails": data.get('thumbnails', []), "songs": songs, "albums": albums, "singles": singles}
    except Exception as e:
        return {"error": str(e), "name": "Error", "songs": [], "albums": [], "singles": []}

@app.get("/api/album/{bid}")
def album(bid: str):
    data = ytmusic.get_album(bid)
    tracks = [{"title": t.get('title'), "duration": t.get('duration'), "videoId": t.get('videoId'), "artists": [{"id": a.get('id'), "name": a.get('name')} for a in t.get('artists',[])]} for t in data.get('tracks',[])]
    data['tracks'] = tracks
    return data

# --- UPDATED STREAMING ---
@app.get("/api/stream")
def stream(videoId: str = None, query: str = None):
    print(f"Streaming request: videoId={videoId}, query={query}")
    ydl_opts = {
        'format': 'bestaudio[ext=m4a]/bestaudio[ext=webm]/bestaudio',
        'quiet': True,
        'no_warnings': True,
        'skip_download': True,
        'noplaylist': True,
        'nocheckcertificate': True,
        'extractor_args': {'youtube': {'player_client': ['android', 'web']}},
        'http_headers': {
            'User-Agent': 'com.google.android.youtube/19.09.37 (Linux; U; Android 11) gzip',
            'Referer': 'https://www.youtube.com/',
        },
    }
    target = f"https://www.youtube.com/watch?v={videoId}" if videoId else f"ytsearch1:{query}"
    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(target, download=False)
            if 'entries' in info:
                info = info['entries'][0]
            url = info.get('url')
            if not url:
                return {"error": "No URL"}
            # Return direct URL – WebView can play m4a reliably
            return {"url": url, "title": info.get('title'), "id": info.get('id')}
    except Exception as e:
        print(f"Stream error: {e}")
        return {"error": str(e)}

_server_thread = None

def start_server():
    global _server_thread
    if _server_thread is not None:
        print("Server already running")
        return

    import uvicorn
    def run():
        try:
            uvicorn.run(app, host="127.0.0.1", port=8000, log_level="info")
        except Exception as e:
            print(f"Uvicorn error: {e}")

    _server_thread = threading.Thread(target=run, daemon=True)
    _server_thread.start()
    print("Server started")
