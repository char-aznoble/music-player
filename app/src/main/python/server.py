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
    try:
        artists_raw = ytmusic.search(q, filter='artists', limit=12)
        songs_raw = ytmusic.search(q, filter='songs', limit=12)
        albums_raw = ytmusic.search(q, filter='albums', limit=12)

        artists = []
        for a in artists_raw:
            # Check for any possible ID field
            bid = a.get('browseId') or a.get('channelId') or a.get('id')
            if bid:
                # Check for any possible name field
                name = a.get('artist') or a.get('name') or a.get('title')
                if name:
                    artists.append({
                        "id": bid,
                        "browseId": bid,
                        "name": name,
                        "thumbnails": a.get('thumbnails', [])
                    })

        songs = []
        for s in songs_raw:
            vid = s.get('videoId')
            if vid:
                title = s.get('title')
                # Get artist name safely
                raw_artists = s.get('artists') or []
                artist_name = raw_artists[0].get('name') if raw_artists else (s.get('artist') or s.get('name') or '')

                songs.append({
                    "id": vid,
                    "videoId": vid,
                    "title": title,
                    "artist": artist_name,
                    "artists": [{"id": art.get('id') or art.get('browseId'), "name": art.get('name')} for art in raw_artists],
                    "thumbnails": s.get('thumbnails', []),
                    "duration": s.get('duration')
                })

        albums = []
        for al in albums_raw:
            bid = al.get('browseId') or al.get('id')
            if bid:
                title = al.get('title') or al.get('name')
                raw_artists = al.get('artists') or []
                artist_name = raw_artists[0].get('name') if raw_artists else (al.get('artist') or al.get('name') or '')

                albums.append({
                    "id": bid,
                    "browseId": bid,
                    "title": title,
                    "artist": artist_name,
                    "artists": [{"id": art.get('id') or art.get('browseId'), "name": art.get('name')} for art in raw_artists],
                    "year": al.get('year'),
                    "thumbnails": al.get('thumbnails', [])
                })

        return {"artists": artists, "songs": songs, "albums": albums}
    except Exception as e:
        print(f"Search error: {e}")
        return {"error": str(e), "artists": [], "songs": [], "albums": []}

@app.get("/api/artist/{bid}")
def artist(bid: str):
    try:
        data = ytmusic.get_artist(bid)

        # Helper to extract results from a category (songs, albums, etc.)
        def get_results(key):
            category = data.get(key, {})
            if isinstance(category, list): return category
            return category.get('results', [])

        # Extract popular songs
        songs_raw = get_results('songs')
        songs = []
        for s in songs_raw:
            songs.append({
                "videoId": s.get('videoId'),
                "title": s.get('title'),
                "thumbnails": s.get('thumbnails', []),
                "duration": s.get('duration'),
                "artists": [{"id": a.get('id'), "name": a.get('name')} for a in s.get('artists', [])]
            })

        # Extract albums
        albums_raw = get_results('albums')
        albums = [{"id": a.get('browseId'), "title": a.get('title'), "thumbnails": a.get('thumbnails', []), "year": a.get('year'), "type": "Album"} for a in albums_raw]

        # Extract singles
        singles_raw = get_results('singles')
        singles = [{"id": s.get('browseId'), "title": s.get('title'), "thumbnails": s.get('thumbnails', []), "year": s.get('year'), "type": "Single"} for s in singles_raw]

        return {
            "name": data.get('name', 'Unknown Artist'),
            "description": data.get('description'),
            "views": data.get('views'),
            "subscribers": data.get('subscribers'),
            "thumbnails": data.get('thumbnails', []),
            "songs": songs,
            "albums": albums,
            "singles": singles,
            "albumsBrowseId": data.get('albums', {}).get('browseId') if isinstance(data.get('albums'), dict) else None,
            "albumsParams": data.get('albums', {}).get('params') if isinstance(data.get('albums'), dict) else None,
            "singlesBrowseId": data.get('singles', {}).get('browseId') if isinstance(data.get('singles'), dict) else None,
            "singlesParams": data.get('singles', {}).get('params') if isinstance(data.get('singles'), dict) else None
        }
    except Exception as e:
        return {"error": str(e), "name": "Error Loading Artist", "songs": [], "albums": [], "singles": []}

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
    # The user wants to "scrape from youtube.com" for audio
    ydl_opts = {
        'format': 'bestaudio/best',
        'quiet': True,
        'no_warnings': True,
        'default_search': 'ytsearch',
        'skip_download': True,
        'noplaylist': True,
        'nocheckcertificate': True,
    }

    # If a query is provided (Title + Artist), use it to search YouTube.
    # Otherwise, fall back to the videoId URL.
    if query:
        search_target = f"ytsearch1:{query}"
    else:
        search_target = f"https://www.youtube.com/watch?v={videoId}"

    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(search_target, download=False)
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
