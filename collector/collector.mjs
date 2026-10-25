import { createServer } from "node:http";
import { createHash, randomUUID } from "node:crypto";
import { ChzzkClient } from "chzzk";

const PORT = Number(process.env.COLLECTOR_PORT || 3211);
const client = new ChzzkClient();
const channels = new Map();
const events = [];
const sseClients = new Set();
const settings = { pluginUrl: "http://127.0.0.1:8765/api/chzzk/donation", pluginToken: "", forwardDonations: false };
const MAX_EVENTS = 1_000;

function json(response, status, body) {
  response.writeHead(status, { "Content-Type": "application/json; charset=utf-8", "Access-Control-Allow-Origin": "http://localhost:3000" });
  response.end(JSON.stringify(body));
}

function eventId(value) {
  return createHash("sha256").update(JSON.stringify(value)).digest("hex").slice(0, 32);
}

function pushEvent(type, channelId, data, source = "chzzk") {
  const item = {
    id: eventId({ type, channelId, data, nonce: randomUUID() }),
    type,
    channelId,
    source,
    receivedAt: new Date().toISOString(),
    data,
  };
  events.unshift(item);
  if (events.length > MAX_EVENTS) events.pop();
  const line = `data: ${JSON.stringify(item)}\n\n`;
  for (const clientResponse of sseClients) clientResponse.write(line);
  if (type === "donation" && settings.forwardDonations) forwardDonation(item);
  return item;
}

async function forwardDonation(item) {
  const amount = Number(item.data.amount);
  if (!Number.isSafeInteger(amount) || amount <= 0 || !settings.pluginToken) return;
  try {
    const response = await fetch(settings.pluginUrl, {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${settings.pluginToken}` },
      body: JSON.stringify({ channelId: item.channelId, amount, donorName: item.data.nickname || "익명", eventId: item.id }),
      signal: AbortSignal.timeout(5_000),
    });
    if (!response.ok) {
      pushEvent("system", item.channelId, { message: "마인크래프트 플러그인이 후원을 거절했습니다.", detail: `HTTP ${response.status}` }, "collector");
    }
  } catch (error) {
    pushEvent("system", item.channelId, { message: "마인크래프트 플러그인 전달 실패", detail: String(error) }, "collector");
  }
}

function displayName(value) {
  return value?.profile?.nickname || value?.nickname || value?.senderNickname || "익명";
}

function asMessage(value) {
  return value?.message ?? value?.content ?? value?.extras?.description ?? "";
}

function classifyRaw(raw) {
  const text = typeof raw === "string" ? raw : JSON.stringify(raw);
  const upper = text.toUpperCase();
  if (upper.includes("MISSION") || upper.includes("PARTY")) return "party";
  if (upper.includes("DONATION")) return "donation-raw";
  if (upper.includes("SUBSCRIPTION")) return "subscription";
  if (upper.includes("CHAT")) return "chat-raw";
  return "raw";
}

async function connectChannel(channelId) {
  if (channels.has(channelId)) return channels.get(channelId);
  const state = { channelId, status: "connecting", chat: null, startedAt: new Date().toISOString(), error: null };
  channels.set(channelId, state);
  pushEvent("system", channelId, { message: "채널 연결을 시작했습니다." }, "collector");
  try {
    const chat = client.chat({ channelId, pollInterval: 30_000 });
    state.chat = chat;
    chat.on("connect", (chatChannelId) => {
      state.status = "connected";
      pushEvent("system", channelId, { message: "채팅 연결됨", chatChannelId }, "collector");
      chat.requestRecentChat?.(50);
    });
    chat.on("reconnect", (chatChannelId) => pushEvent("system", channelId, { message: "채팅 재연결됨", chatChannelId }, "collector"));
    chat.on("chat", (chatEvent) => pushEvent("chat", channelId, {
      nickname: displayName(chatEvent), message: asMessage(chatEvent), hidden: Boolean(chatEvent?.hidden), raw: chatEvent,
    }));
    chat.on("donation", (donation) => pushEvent("donation", channelId, {
      nickname: displayName(donation), amount: donation?.extras?.payAmount ?? donation?.extras?.pay_amount,
      message: asMessage(donation), raw: donation,
    }));
    chat.on("systemMessage", (systemMessage) => pushEvent("system", channelId, { message: asMessage(systemMessage), raw: systemMessage }));
    chat.on("blind", (blind) => pushEvent("moderation", channelId, { message: "블라인드 처리", raw: blind }));
    chat.on("notice", (notice) => pushEvent("notice", channelId, { message: asMessage(notice), raw: notice }));
    // Raw frames preserve mission/party and any future event type that the library does not normalize yet.
    chat.on("raw", (raw) => pushEvent(classifyRaw(raw), channelId, { raw }));
    await chat.connect();
  } catch (error) {
    state.status = "error";
    state.error = String(error);
    pushEvent("system", channelId, { message: "채널 연결 실패", detail: state.error }, "collector");
  }
  return state;
}

function stopChannel(channelId) {
  const state = channels.get(channelId);
  if (!state) return false;
  state.chat?.disconnect?.();
  state.chat?.close?.();
  channels.delete(channelId);
  pushEvent("system", channelId, { message: "채널 연결을 종료했습니다." }, "collector");
  return true;
}

function readBody(request) {
  return new Promise((resolve, reject) => {
    let value = "";
    request.on("data", (chunk) => { value += chunk; if (value.length > 64_000) request.destroy(); });
    request.on("end", () => { try { resolve(value ? JSON.parse(value) : {}); } catch { reject(new Error("JSON 본문이 올바르지 않습니다.")); } });
    request.on("error", reject);
  });
}

const server = createServer(async (request, response) => {
  if (request.method === "OPTIONS") { response.writeHead(204, { "Access-Control-Allow-Origin": "http://localhost:3000", "Access-Control-Allow-Headers": "Content-Type", "Access-Control-Allow-Methods": "GET,POST,DELETE,OPTIONS" }); response.end(); return; }
  const url = new URL(request.url, `http://${request.headers.host}`);
  try {
    if (request.method === "GET" && url.pathname === "/health") return json(response, 200, { ok: true });
    if (request.method === "GET" && url.pathname === "/events") return json(response, 200, { events });
    if (request.method === "GET" && url.pathname === "/channels") return json(response, 200, { channels: [...channels.values()].map(({ chat, ...entry }) => entry) });
    if (request.method === "GET" && url.pathname === "/settings") return json(response, 200, { ...settings, pluginToken: settings.pluginToken ? "configured" : "" });
    if (request.method === "GET" && url.pathname === "/events/stream") {
      response.writeHead(200, { "Content-Type": "text/event-stream", "Cache-Control": "no-cache", Connection: "keep-alive", "Access-Control-Allow-Origin": "http://localhost:3000" });
      response.write("retry: 2000\n\n"); sseClients.add(response); request.on("close", () => sseClients.delete(response)); return;
    }
    if (request.method === "POST" && url.pathname === "/channels") {
      const { channelId } = await readBody(request);
      if (typeof channelId !== "string" || !/^[A-Za-z0-9_-]{3,128}$/.test(channelId)) return json(response, 400, { ok: false, message: "올바른 치지직 채널 ID를 입력하세요." });
      const state = await connectChannel(channelId); return json(response, state.status === "error" ? 502 : 202, { ok: state.status !== "error", channel: { ...state, chat: undefined } });
    }
    if (request.method === "DELETE" && url.pathname.startsWith("/channels/")) return json(response, 200, { ok: stopChannel(decodeURIComponent(url.pathname.slice(10))) });
    if (request.method === "POST" && url.pathname === "/settings") {
      const body = await readBody(request);
      if (typeof body.pluginUrl === "string") settings.pluginUrl = body.pluginUrl;
      if (typeof body.pluginToken === "string" && body.pluginToken !== "configured") settings.pluginToken = body.pluginToken;
      settings.forwardDonations = Boolean(body.forwardDonations);
      return json(response, 200, { ok: true });
    }
    return json(response, 404, { ok: false, message: "없는 경로입니다." });
  } catch (error) { return json(response, 500, { ok: false, message: String(error) }); }
});

server.listen(PORT, "127.0.0.1", () => console.log(`Chzzk collector: http://127.0.0.1:${PORT}`));
