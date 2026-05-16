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
    # Extract popular songs
    songs_raw = data.get('songs', {}).get('results', [])
    songs = [{"videoId": s.get('videoId'), "title": s.get('title'), "thumbnails": s.get('thumbnails', []), "duration": s.get('duration'), "artists": [{"id": a.get('id'), "name": a.get('name')} for a in s.get('artists', [])]} for s in songs_raw]

    # Extract albums
    albums_raw = data.get('albums', {}).get('results', [])
    albums = [{"id": a.get('browseId'), "title": a.get('title'), "thumbnails": a.get('thumbnails', []), "year": a.get('year'), "type": "Album"} for a in albums_raw]

    # Extract singles
    singles_raw = data.get('singles', {}).get('results', [])
    singles = [{"id": s.get('browseId'), "title": s.get('title'), "thumbnails": s.get('thumbnails', []), "year": s.get('year'), "type": "Single"} for s in singles_raw]

    return {
        "name": data.get('name'),
        "description": data.get('description'),
        "views": data.get('views'),
        "subscribers": data.get('subscribers'),
        "thumbnails": data.get('thumbnails', []),
        "songs": songs,
        "albums": albums,
        "singles": singles,
        "albumsBrowseId": data.get('albums', {}).get('browseId'),
        "albumsParams": data.get('albums', {}).get('params'),
        "singlesBrowseId": data.get('singles', {}).get('browseId'),
        "singlesParams": data.get('singles', {}).get('params')
    }

@app.get("/api/artist_albums")
def artist_albums(browseId: str, params: str):
    data = ytmusic.get_artist_albums(browseId, params)
    albums = [{"id": a.get('browseId'), "title": a.get('title'), "thumbnails": a.get('thumbnails', []), "year": a.get('year'), "type": a.get('type')} for a in data]
    return {"albums": albums}

@app.get("/api/album/{bid}")
def album(bid: str):
    data = ytmusic.get_album(bid)
    tracks = [{"title": t.get('title'), "duration": t.get('duration'), "videoId": t.get('videoId'), "artists": [{"id": a.get('id'), "name": a.get('name')} for a in t.get('artists',[])]} for t in data.get('tracks',[])]
    data['tracks'] = tracks
    return data

@app.get("/api/stream")
def stream(videoId: str = None, query: str = None):
    # If we have a videoId, we can still use it, but we'll prioritize a search-based
    # approach if specifically requested, or just use the videoId to get the direct URL.
    # The user wants to "scrape from youtube.com".

    ydl_opts = {
        'format': 'bestaudio/best',
        'quiet': True,
        'no_warnings': True,
        'default_search': 'ytsearch',
        'skip_download': True,
        'noplaylist': True,
        'nocheckcertificate': True,
    }

    # Construct a search query if only videoId is provided,
    # or use the provided query (title + artist)
    search_query = query
    if not search_query and videoId:
        search_query = f"https://www.youtube.com/watch?v={videoId}"

    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(search_query, download=False)
            if 'entries' in info:
                info = info['entries'][0]

            return {
                "url": info.get('url'),
                "title": info.get('title'),
                "webpage_url": info.get('webpage_url')
            }
    except Exception as e:
        return {"error": str(e)}
def start_server():
    import uvicorn
    def run():
        uvicorn.run(app, host="127.0.0.1", port=8000, log_level="error")
    t = threading.Thread(target=run, daemon=True)
    t.start()
