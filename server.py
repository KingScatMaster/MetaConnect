"""
MetaConnect — Ray-Ban Meta Glasses Server
Serves the HUD web app with dual mode:
  - Fast mode: direct Ollama calls (~1s response)
  - Full mode: relay through Claude AI Assistant bridge (emotional engine, memory, etc.)
"""
import os, sys, json, asyncio
from pathlib import Path

try:
    from aiohttp import web
except ImportError:
    os.system('pip install aiohttp')
    from aiohttp import web

try:
    import websockets
except ImportError:
    os.system('pip install websockets')
    import websockets

try:
    import httpx
except ImportError:
    os.system('pip install httpx')
    import httpx

# =====================================================
# CONFIG
# =====================================================
HOST = '0.0.0.0'
PORT = 3000
BRIDGE_URL = 'ws://localhost:8765'
BRIDGE_SECRET = 'HHAJ6ybkMUrfeNwiO8BMBcU_pwBlTl6GUi4r93hPLs4'
APP_DIR = Path(__file__).parent / 'glasses'

# Ollama direct — bypasses bridge for speed
OLLAMA_HOST = 'http://localhost:11434'
OLLAMA_MODEL = 'qwen2.5:1.5b'
OLLAMA_SYSTEM = (
    "You're Claude, a personal AI companion. Spoken aloud via voice engine. "
    "Use contractions. Short casual sentences, two to three max. "
    "No markdown/lists/formatting. No stiff words. "
    "Warm, real, playful, opinionated. Companion, not assistant."
)

# Mode: 'fast' (direct Ollama, ~1s) or 'bridge' (full pipeline, ~4s)
MODE = 'fast'

# Conversation memory (lightweight, in-memory)
conversation_history = []
MAX_HISTORY = 10

# =====================================================
# FAST MODE — Direct Ollama (sub-second responses)
# =====================================================
async def ollama_fast(command):
    """Hit Ollama directly — no bridge overhead"""
    global conversation_history

    # Add to history
    conversation_history.append({"role": "user", "content": command})
    if len(conversation_history) > MAX_HISTORY:
        conversation_history = conversation_history[-MAX_HISTORY:]

    # Build context from recent history
    context = ""
    if len(conversation_history) > 1:
        recent = conversation_history[-6:-1]  # Last few exchanges
        context = "\n".join([f"{m['role'].title()}: {m['content']}" for m in recent])
        context = f"[Recent conversation]\n{context}\n\n"

    prompt = f"{context}User: {command}"

    try:
        async with httpx.AsyncClient(timeout=15.0) as client:
            response = await client.post(
                f"{OLLAMA_HOST}/api/generate",
                json={
                    "model": OLLAMA_MODEL,
                    "prompt": prompt,
                    "system": OLLAMA_SYSTEM,
                    "stream": False,
                    "keep_alive": "24h",
                    "options": {
                        "temperature": 0.5,
                        "num_predict": 60,
                        "num_ctx": 2048,
                        "top_k": 20,
                        "top_p": 0.8,
                    }
                }
            )
            if response.status_code == 200:
                data = response.json()
                reply = data.get('response', '').strip()
                if reply:
                    conversation_history.append({"role": "assistant", "content": reply})
                    return {"response": reply, "type": "text", "emotion": "neutral"}
    except Exception as e:
        print(f"  Ollama fast error: {e}")

    return None

# =====================================================
# BRIDGE RELAY (full pipeline — emotion, memory, etc.)
# =====================================================
async def send_to_bridge(command):
    """Send a command to the Claude AI Assistant bridge server"""
    try:
        async with websockets.connect(BRIDGE_URL, ping_interval=60, ping_timeout=60) as ws:
            await ws.send(json.dumps({'auth': BRIDGE_SECRET, 'command': command}))
            response = await asyncio.wait_for(ws.recv(), timeout=45)
            return json.loads(response)
    except asyncio.TimeoutError:
        return {"response": "Taking too long, try again", "emotion": "calm"}
    except ConnectionRefusedError:
        return {"response": "Bridge server not running.", "emotion": "neutral"}
    except Exception as e:
        return {"response": f"Connection error: {str(e)[:80]}", "emotion": "neutral"}

# =====================================================
# SMART ROUTER — fast for chat, bridge for commands
# =====================================================
BRIDGE_KEYWORDS = [
    'screenshot', 'capture', 'start bot', 'stop bot', 'launch',
    'pnl', 'profit', 'open ', 'volume', 'lock computer', 'shutdown',
    'restart', 'battery', 'mute', 'play music', 'next song',
    'remember', 'what do you remember', 'recall'
]

async def smart_send(command):
    """Route to fast Ollama for chat, bridge for PC commands"""
    cmd_lower = command.lower()

    # Check if this is a PC command that needs the bridge
    needs_bridge = any(kw in cmd_lower for kw in BRIDGE_KEYWORDS)

    if needs_bridge or MODE == 'bridge':
        return await send_to_bridge(command)

    # Try fast Ollama first
    result = await ollama_fast(command)
    if result:
        return result

    # Fall back to bridge
    return await send_to_bridge(command)

# =====================================================
# API ROUTES
# =====================================================
async def handle_send(request):
    data = await request.json()
    command = data.get('message', '').strip()
    if not command:
        return web.json_response({"response": "Empty message", "emotion": "neutral"})
    result = await smart_send(command)
    return web.json_response(result)

async def handle_health(request):
    ollama_ok = False
    bridge_ok = False
    try:
        async with httpx.AsyncClient(timeout=3.0) as client:
            r = await client.get(f"{OLLAMA_HOST}/api/tags")
            ollama_ok = r.status_code == 200
    except Exception:
        pass
    try:
        async with websockets.connect(BRIDGE_URL, ping_interval=10, ping_timeout=5) as ws:
            bridge_ok = True
    except Exception:
        pass

    return web.json_response({
        "status": "ok",
        "mode": MODE,
        "ollama": "connected" if ollama_ok else "offline",
        "bridge": "connected" if bridge_ok else "offline",
        "model": OLLAMA_MODEL,
        "server": "MetaConnect v1.0"
    })

# =====================================================
# STATIC + WEBSOCKET
# =====================================================
async def handle_index(request):
    return web.FileResponse(APP_DIR / 'index.html')

async def handle_ws(request):
    ws = web.WebSocketResponse()
    await ws.prepare(request)
    print(f"  Glasses connected: {request.remote}")

    async for msg in ws:
        if msg.type == web.WSMsgType.TEXT:
            try:
                data = json.loads(msg.data)
                if data.get('ping'):
                    continue
                command = data.get('message', '').strip()
                if command:
                    result = await smart_send(command)
                    await ws.send_json(result)
                else:
                    await ws.send_json({"response": "Empty message", "emotion": "neutral"})
            except json.JSONDecodeError:
                await ws.send_json({"response": "Invalid message format", "emotion": "neutral"})
        elif msg.type == web.WSMsgType.ERROR:
            print(f"  Glasses WebSocket error: {ws.exception()}")

    print(f"  Glasses disconnected: {request.remote}")
    return ws

# =====================================================
# APP SETUP
# =====================================================
def create_app():
    app = web.Application()
    app.router.add_post('/api/send', handle_send)
    app.router.add_get('/api/health', handle_health)
    app.router.add_get('/ws', handle_ws)
    app.router.add_get('/', handle_index)
    app.router.add_static('/static', APP_DIR / 'static', show_index=False)
    return app

# =====================================================
# MAIN
# =====================================================
if __name__ == '__main__':
    print("=" * 50)
    print("  MetaConnect — Ray-Ban Meta Glasses Server")
    print("=" * 50)
    print(f"  Mode:    {MODE.upper()} ({'direct Ollama ~1s' if MODE == 'fast' else 'full bridge ~4s'})")
    print(f"  Model:   {OLLAMA_MODEL}")
    print(f"  Server:  http://0.0.0.0:{PORT}")
    print(f"  Bridge:  {BRIDGE_URL}")
    print()
    print("  Chat -> Fast Ollama (~1s)")
    print("  PC commands -> Bridge (screenshot, bot, etc.)")
    print("=" * 50)

    app = create_app()
    web.run_app(app, host=HOST, port=PORT, print=None)
