const http = require("http");
const express = require("express");
const { WebSocketServer } = require("ws");

const app = express();
app.use(express.json({ limit: "256kb" }));

const clients = new Map();
const rooms = new Map();

app.get("/", (_req, res) => {
  res.json({
    app: "InternetChat",
    status: "online",
    service: "messaging-and-call-signaling",
    version: "1.0.0"
  });
});

app.get("/health", (_req, res) => {
  res.json({
    ok: true,
    clients: clients.size,
    rooms: rooms.size,
    uptime: Math.floor(process.uptime())
  });
});

const server = http.createServer(app);
const wss = new WebSocketServer({ server });

function send(ws, data) {
  if (ws.readyState === 1) ws.send(JSON.stringify(data));
}

function broadcastRoom(roomId, data, except) {
  const room = rooms.get(roomId);
  if (!room) return;
  for (const ws of room) {
    if (ws !== except) send(ws, data);
  }
}

function removeClient(ws) {
  if (ws.userId && clients.get(ws.userId) === ws) {
    clients.delete(ws.userId);
  }

  if (ws.roomId) {
    const room = rooms.get(ws.roomId);
    if (room) {
      room.delete(ws);
      broadcastRoom(ws.roomId, { type: "peer-left", userId: ws.userId });
      if (room.size === 0) rooms.delete(ws.roomId);
    }
  }
}

wss.on("connection", (ws) => {
  ws.userId = null;
  ws.roomId = null;

  send(ws, { type: "connected" });

  ws.on("message", (raw) => {
    let msg;
    try {
      msg = JSON.parse(raw.toString());
    } catch {
      return send(ws, { type: "error", message: "Invalid JSON" });
    }

    if (msg.type === "identify") {
      const userId = String(msg.userId || "").trim();
      if (!userId || userId.length > 80) {
        return send(ws, { type: "error", message: "Invalid userId" });
      }

      if (clients.has(userId)) {
        const old = clients.get(userId);
        if (old !== ws) old.close();
      }

      ws.userId = userId;
      clients.set(userId, ws);
      return send(ws, { type: "identified", userId });
    }

    if (msg.type === "join-room") {
      if (!ws.userId) return send(ws, { type: "error", message: "Identify first" });

      const roomId = String(msg.roomId || "").trim();
      if (!roomId || roomId.length > 120) {
        return send(ws, { type: "error", message: "Invalid roomId" });
      }

      if (ws.roomId) removeClient(ws);

      let room = rooms.get(roomId);
      if (!room) {
        room = new Set();
        rooms.set(roomId, room);
      }

      const peers = [...room].map((peer) => peer.userId).filter(Boolean);
      room.add(ws);
      ws.roomId = roomId;

      send(ws, { type: "room-joined", roomId, peers });
      broadcastRoom(roomId, { type: "peer-joined", userId: ws.userId }, ws);
      return;
    }

    if (msg.type === "chat") {
      if (!ws.userId) return send(ws, { type: "error", message: "Identify first" });
      if (!ws.roomId) return send(ws, { type: "error", message: "Join a room first" });

      const text = String(msg.text || "").trim();
      if (!text || text.length > 4000) return;

      broadcastRoom(ws.roomId, {
        type: "chat",
        from: ws.userId,
        text,
        timestamp: Date.now()
      });
      return send(ws, {
        type: "chat",
        from: ws.userId,
        text,
        timestamp: Date.now(),
        self: true
      });
    }

    if (["offer", "answer", "ice"].includes(msg.type)) {
      if (!ws.userId || !ws.roomId) return;

      const targetId = String(msg.target || "");
      const target = clients.get(targetId);
      if (!target) {
        return send(ws, { type: "error", message: "Target peer is offline" });
      }

      send(target, {
        type: msg.type,
        from: ws.userId,
        data: msg.data
      });
      return;
    }

    if (msg.type === "leave-room") {
      removeClient(ws);
      ws.roomId = null;
      return send(ws, { type: "left-room" });
    }

    send(ws, { type: "error", message: "Unknown message type" });
  });

  ws.on("close", () => removeClient(ws));
  ws.on("error", () => removeClient(ws));
});

const PORT = Number(process.env.PORT || 3000);
server.listen(PORT, "0.0.0.0", () => {
  console.log(`InternetChat server listening on ${PORT}`);
});
