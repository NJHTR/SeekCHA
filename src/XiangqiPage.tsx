import { useEffect, useRef, useState, type FormEvent } from "react";
import {
  Check, ChevronRight, Clock3, Coins, Copy, Flag, Gift, Globe2, Handshake, History, LogIn,
  LoaderCircle, MessageCircle, Plus, RotateCcw, Search, Send, ShieldAlert, Swords, Trophy, UserRound, Users, X,
} from "lucide-react";
import { Button, Dialog, StatusBadge, TextField, Tooltip } from "@seekwd/ui";
import { dormApi, type ApiXiangqiAdSession, type ApiXiangqiColor, type ApiXiangqiMessage, type ApiXiangqiMove, type ApiXiangqiGame, type ApiXiangqiProfile } from "./api";

const PIECE_LABELS: Record<string, string> = {
  rK: "帅", rA: "仕", rB: "相", rN: "马", rR: "车", rC: "炮", rP: "兵",
  bK: "将", bA: "士", bB: "象", bN: "马", bR: "车", bC: "炮", bP: "卒",
};

const FINISH_LABELS: Record<string, string> = {
  checkmate: "将死",
  stalemate: "困毙",
  resignation: "认输",
  agreement: "双方议和",
  sixty_move: "六十回合自然限着",
  timeout: "超时判负",
  game_limit: "达到 15 分钟上限，和棋",
};

function gameTime(value: string) {
  try {
    return new Intl.DateTimeFormat("zh-CN", {
      timeZone: "Asia/Shanghai", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit",
    }).format(new Date(value));
  } catch { return value; }
}

function formatDuration(milliseconds: number) {
  const totalSeconds = Math.floor(milliseconds / 1000);
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  return hours ? `${hours.toString().padStart(2, "0")}:${minutes.toString().padStart(2, "0")}:${seconds.toString().padStart(2, "0")}`
    : `${minutes.toString().padStart(2, "0")}:${seconds.toString().padStart(2, "0")}`;
}

function ratingTitle(rating: number) {
  // 本站天梯采用“业/学/专”三组九级显示；积分仍由 Elo 结算。
  const score = Math.max(1000, Math.round(rating));
  const index = Math.floor((score - 1000) / 100);
  const group = index < 9 ? "业" : index < 18 ? "学" : "专";
  const level = Math.min(9, (index % 9) + 1);
  return `${group}${level}`;
}

export function XiangqiPage({ onNotice }: { onNotice: (message: string) => void }) {
  const [games, setGames] = useState<ApiXiangqiGame[]>([]);
  const [game, setGame] = useState<ApiXiangqiGame | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [joining, setJoining] = useState(false);
  const [joinCode, setJoinCode] = useState("");
  const [selectedSquare, setSelectedSquare] = useState<string | null>(null);
  const [error, setError] = useState("");
  const [confirmResign, setConfirmResign] = useState(false);
  const [profile, setProfile] = useState<ApiXiangqiProfile | null>(null);
  const [matching, setMatching] = useState(false);
  const [matchElapsed, setMatchElapsed] = useState(0);
  const [messages, setMessages] = useState<ApiXiangqiMessage[]>([]);
  const [chatInput, setChatInput] = useState("");
  const [recordOpen, setRecordOpen] = useState(false);
  const [recordTab, setRecordTab] = useState<"chat" | "moves">("chat");
  const [bubble, setBubble] = useState<ApiXiangqiMessage | null>(null);
  const [adSession, setAdSession] = useState<ApiXiangqiAdSession | null>(null);
  const [adSeconds, setAdSeconds] = useState(0);
  const [adOfferOpen, setAdOfferOpen] = useState(false);
  const [reliefOpen, setReliefOpen] = useState(false);
  const [clockNow, setClockNow] = useState(Date.now());
  const [elapsedNow, setElapsedNow] = useState(Date.now());
  const lastMessageId = useRef("");
  const previousStatus = useRef<string | null>(null);

  const refreshGames = async () => {
    try {
      const items = await dormApi.xiangqiGames();
      setGames(items);
      if (!game && items.length) setGame(items[0]);
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "无法读取棋局");
    } finally { setLoading(false); }
  };

  useEffect(() => { void refreshGames(); }, []);

  useEffect(() => {
    let active = true;
    Promise.all([dormApi.xiangqiProfile(), dormApi.xiangqiMatchStatus()]).then(([wallet, match]) => {
      if (!active) return;
      setProfile(wallet);
      if (wallet.ads_used_today < wallet.ads_limit && !match.game && match.status !== "searching") setAdOfferOpen(true);
      if (match.status === "searching") setMatching(true);
      if (match.status === "matched" && match.game) setGame(match.game);
    }).catch(() => {});
    return () => { active = false; };
  }, []);

  useEffect(() => {
    if (!matching) return;
    const started = Date.now();
    setMatchElapsed(0);
    const elapsedTimer = window.setInterval(() => setMatchElapsed(Math.floor((Date.now() - started) / 1000)), 1000);
    const poll = () => void dormApi.xiangqiMatchStatus().then((result) => {
      if (result.status === "matched" && result.game) {
        setMatching(false);
        setGame(result.game);
        void dormApi.xiangqiProfile().then(setProfile);
        onNotice("匹配成功，对局已经开始");
      }
    }).catch(() => {});
    poll();
    const timer = window.setInterval(poll, 1200);
    return () => { window.clearInterval(timer); window.clearInterval(elapsedTimer); };
  }, [matching]);

  useEffect(() => {
    const timer = window.setInterval(() => { setClockNow(Date.now()); setElapsedNow(Date.now()); }, 200);
    return () => window.clearInterval(timer);
  }, []);

  useEffect(() => {
    if (!game) { previousStatus.current = null; return; }
    if (previousStatus.current === "active" && game.status === "finished"
      && profile && profile.ads_used_today < profile.ads_limit) setAdOfferOpen(true);
    previousStatus.current = game.status;
  }, [game?.id, game?.status, profile?.ads_used_today]);

  useEffect(() => {
    if (!game) { setMessages([]); return; }
    let active = true;
    const load = () => void dormApi.xiangqiMessages(game.id).then((items) => {
      if (!active) return;
      const latest = items[items.length - 1];
      if (latest && latest.id !== lastMessageId.current) {
        if (lastMessageId.current && !latest.mine) setBubble(latest);
        lastMessageId.current = latest.id;
      }
      setMessages(items);
    }).catch(() => {});
    load();
    const timer = window.setInterval(load, 1400);
    return () => { active = false; window.clearInterval(timer); };
  }, [game?.id]);

  useEffect(() => {
    if (!bubble) return;
    const timer = window.setTimeout(() => setBubble(null), 4200);
    return () => window.clearTimeout(timer);
  }, [bubble?.id]);

  useEffect(() => {
    if (!adSession) return;
    const tick = () => setAdSeconds(Math.max(0, Math.ceil((new Date(adSession.available_at).getTime() - Date.now()) / 1000)));
    tick();
    const timer = window.setInterval(tick, 250);
    return () => window.clearInterval(timer);
  }, [adSession?.session_id]);

  useEffect(() => {
    if (!game || game.status === "finished") return;
    const gameId = game.id;
    let cancelled = false;
    const timer = window.setInterval(() => {
      if (document.hidden) return;
      void dormApi.xiangqiGame(gameId).then((next) => {
        if (!cancelled) {
          setGame((current) => current?.id === gameId ? next : current);
          if (game.status !== "active" && next.status === "active") void dormApi.xiangqiProfile().then(setProfile);
          if (next.status === "finished") void dormApi.xiangqiProfile().then(setProfile);
        }
      }).catch((caught) => {
        if (!cancelled && caught instanceof Error && caught.message.includes("过期")) setError(caught.message);
      });
    }, 1400);
    return () => { cancelled = true; window.clearInterval(timer); };
  }, [game?.id, game?.status]);

  useEffect(() => {
    setSelectedSquare(null);
  }, [game?.version]);

  const chooseGame = (next: ApiXiangqiGame) => {
    setGame(next);
    setSelectedSquare(null);
    setError("");
  };

  const createRoom = async () => {
    setBusy(true);
    setError("");
    try {
      const next = await dormApi.createXiangqiGame();
      setGame(next);
      setGames((current) => [next, ...current.filter((item) => item.id !== next.id)]);
      onNotice("房间已创建，把房间号发给对手即可");
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "创建房间失败");
      await maybeOpenRelief(caught);
    } finally { setBusy(false); }
  };

  const joinRoom = async (event: FormEvent) => {
    event.preventDefault();
    const code = joinCode.trim().toUpperCase();
    if (code.length !== 6) { setError("请输入 6 位房间号"); return; }
    setJoining(true);
    setError("");
    try {
      const next = await dormApi.joinXiangqiGame(code);
      setGame(next);
      setJoinCode("");
      setGames((current) => [next, ...current.filter((item) => item.id !== next.id)]);
      onNotice("已加入棋局");
      void dormApi.xiangqiProfile().then(setProfile);
      void refreshGames();
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "加入房间失败");
      await maybeOpenRelief(caught);
    } finally { setJoining(false); }
  };

  const selectSquare = async (square: string) => {
    if (!game || !game.my_turn || busy) return;
    const piece = game.board.squares[square];
    const legalTargets = selectedSquare ? game.legal_moves[selectedSquare] ?? [] : [];
    if (selectedSquare && legalTargets.includes(square)) {
      setBusy(true);
      setError("");
      try {
        const next = await dormApi.moveXiangqi(game.id, selectedSquare, square, game.version);
        setGame(next);
        setSelectedSquare(null);
        setGames((current) => current.map((item) => item.id === next.id ? next : item));
      } catch (caught) {
        setError(caught instanceof Error ? caught.message : "落子失败");
        try { setGame(await dormApi.xiangqiGame(game.id)); } catch { /* Polling retries the view. */ }
      } finally { setBusy(false); }
      return;
    }
    if (piece?.startsWith(game.my_color === "red" ? "r" : "b") && game.legal_moves[square]?.length) {
      setSelectedSquare(square);
      return;
    }
    setSelectedSquare(null);
  };

  const resign = async () => {
    if (!game || busy) return;
    setBusy(true);
    setError("");
    try {
      const next = await dormApi.resignXiangqi(game.id, game.version);
      setGame(next);
      setConfirmResign(false);
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "认输失败");
    } finally { setBusy(false); }
  };

  const drawAction = async (action: "offer" | "accept" | "decline") => {
    if (!game || busy) return;
    setBusy(true);
    setError("");
    try {
      const next = await dormApi.drawXiangqi(game.id, action, game.version);
      setGame(next);
      if (action === "offer") onNotice("和棋请求已发送");
      if (action === "accept") onNotice("双方已和棋");
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "议和操作失败");
    } finally { setBusy(false); }
  };

  const copyRoomCode = async () => {
    if (!game) return;
    try { await navigator.clipboard.writeText(game.room_code); onNotice("房间号已复制"); }
    catch { onNotice(`房间号：${game.room_code}`); }
  };

  const startMatch = async () => {
    setBusy(true); setError("");
    try {
      const result = await dormApi.startXiangqiMatch();
      if (result.profile) setProfile(result.profile);
      if (result.status === "matched" && result.game) { setGame(result.game); onNotice("匹配成功"); }
      else { setMatching(true); onNotice("已进入实时匹配队列"); }
    } catch (caught) {
      const message = caught instanceof Error ? caught.message : "匹配失败";
      setError(message);
      await maybeOpenRelief(caught);
    }
    finally { setBusy(false); }
  };

  const maybeOpenRelief = async (caught: unknown) => {
    if (!(caught instanceof Error) || !caught.message.includes("金币不足")) return;
    const wallet = await dormApi.xiangqiProfile().catch(() => null);
    if (wallet) { setProfile(wallet); if (wallet.can_claim_relief) setReliefOpen(true); }
  };

  const cancelMatch = async () => {
    await dormApi.cancelXiangqiMatch().catch(() => {});
    setMatching(false);
  };

  const sendChat = async (event: FormEvent) => {
    event.preventDefault();
    if (!game || !chatInput.trim()) return;
    const body = chatInput.trim(); setChatInput("");
    try {
      const sent = await dormApi.postXiangqiMessage(game.id, body);
      setMessages((current) => [...current, sent]);
      lastMessageId.current = sent.id;
      setBubble(sent);
    } catch (caught) { setError(caught instanceof Error ? caught.message : "消息发送失败"); }
  };

  const startAd = async () => {
    try { setAdOfferOpen(false); setAdSession(await dormApi.startXiangqiAd()); }
    catch (caught) { setError(caught instanceof Error ? caught.message : "广告暂时不可用"); }
  };

  const claimAd = async () => {
    if (!adSession) return;
    try { setProfile(await dormApi.claimXiangqiAd(adSession.claim_path)); setAdSession(null); setAdOfferOpen(false); onNotice("2500 金币已到账"); }
    catch (caught) { setError(caught instanceof Error ? caught.message : "奖励领取失败"); }
  };

  const claimRelief = async () => {
    try { setProfile(await dormApi.claimXiangqiRelief()); setReliefOpen(false); onNotice("救济金已到账"); }
    catch (caught) { setError(caught instanceof Error ? caught.message : "救济金领取失败"); }
  };

  const clockLabel = (color: ApiXiangqiColor) => {
    if (!game) return "01:30";
    const sampledAt = new Date(game.clock.sampled_at).getTime();
    const elapsed = game.status === "active" && game.clock.running_color === color ? Math.max(0, clockNow - sampledAt) : 0;
    const milliseconds = Math.max(0, game.clock[`${color}_ms`] - elapsed);
    const totalSeconds = Math.ceil(milliseconds / 1000);
    return `${Math.floor(totalSeconds / 60).toString().padStart(2, "0")}:${(totalSeconds % 60).toString().padStart(2, "0")}`;
  };

  const durationLabel = game ? formatDuration(Math.max(0, elapsedNow - new Date(game.clock.started_at || game.created_at).getTime())) : "00:00";

  const topColor: ApiXiangqiColor = game?.my_color === "black" ? "red" : "black";
  const bottomColor: ApiXiangqiColor = topColor === "red" ? "black" : "red";

  return (
    <main className="page-content xiangqi-page">
      <header className="page-heading xiangqi-heading">
        <div>
          <div className="eyebrow">ONLINE XIANGQI</div>
          <h1>双人象棋</h1>
          <p>创建房间，与另一位登录用户实时对弈。</p>
        </div>
        <div className="xiangqi-heading-actions">
          {profile ? <div className="xiangqi-balance"><span><Coins />{profile.coins.toLocaleString("zh-CN")}</span><span><Trophy />{profile.rating}</span><Tooltip content="看广告领取金币"><Button variant="quiet" leadingIcon={<Gift />} aria-label="看广告领取金币" onClick={() => setAdOfferOpen(true)} /></Tooltip></div> : null}
          {game ? <Button variant="quiet" leadingIcon={<Users />} onClick={() => { setGame(null); void refreshGames(); }}>棋局列表</Button> : null}
          <Button leadingIcon={<Plus />} disabled={busy} onClick={() => void createRoom()}>创建房间</Button>
        </div>
      </header>

      {error ? <div className="xiangqi-error" role="alert"><ShieldAlert /><span>{error}</span><button type="button" onClick={() => setError("")}>关闭</button></div> : null}

      {game ? (
        <div className="xiangqi-layout">
          <section className="xiangqi-board-panel" aria-label="中国象棋棋盘">
            <div className="xiangqi-table-head">
              <div className={`xiangqi-player ${game.my_color === topColor ? "is-me" : ""}`}>
                <span className={`xiangqi-player-mark ${topColor}`}>{topColor === "red" ? "帅" : "将"}</span>
                <span><small>{topColor === "red" ? "红方" : "黑方"}</small><strong>{(topColor === "red" ? game.board.red_name : game.board.black_name) || "等待加入"}</strong><time className={`xiangqi-clock${game.turn === topColor && game.status === "active" ? " is-running" : ""}`}>{clockLabel(topColor)}</time></span>
              </div>
              <div className={`xiangqi-turn-indicator ${game.my_turn ? "is-mine" : ""}`}>
                {game.status === "waiting" ? <><Clock3 />等待对手</> : game.status === "finished" ? <><Flag />棋局结束</> : game.in_check ? <><ShieldAlert />{game.turn === game.my_color ? "你被将军" : "对手被将军"}</> : <><span className={`turn-dot ${game.turn}`} />{game.my_turn ? "轮到你" : "等待对手"}</>}
              </div>
              <div className={`xiangqi-player ${game.my_color === bottomColor ? "is-me" : ""}`}>
                <span className={`xiangqi-player-mark ${bottomColor}`}>{bottomColor === "red" ? "帅" : "将"}</span>
                <span><small>{bottomColor === "red" ? "红方" : "黑方"}</small><strong>{(bottomColor === "red" ? game.board.red_name : game.board.black_name) || "等待加入"}</strong><time className={`xiangqi-clock${game.turn === bottomColor && game.status === "active" ? " is-running" : ""}`}>{clockLabel(bottomColor)}</time></span>
              </div>
            </div>

            <Board game={game} selectedSquare={selectedSquare} busy={busy} onSelect={(square) => void selectSquare(square)} />
            <div className="xiangqi-game-meta"><span>第 {game.moves.length + 1} 手</span><i /><span>已进行 {durationLabel}</span><i /><span>每方 1 分 30 秒 · 最长 15 分钟</span></div>

            {bubble ? <div className={`xiangqi-chat-bubble ${bubble.mine ? "is-mine" : ""}`}><strong>{bubble.mine ? "我" : bubble.name}</strong><span>{bubble.body}</span></div> : null}
            {game.status !== "waiting" ? <form className="xiangqi-chat-compose" onSubmit={(event) => void sendChat(event)}><TextField aria-label="对局消息" value={chatInput} maxLength={80} placeholder="说句话，发送后会浮现在棋盘上…" onChange={(event) => setChatInput(event.target.value)} /><Tooltip content="发送消息"><Button type="submit" leadingIcon={<Send />} disabled={!chatInput.trim()}>发送</Button></Tooltip></form> : null}

            {game.status === "waiting" ? <div className="xiangqi-waiting"><span className="waiting-pulse" /><strong>等待第二位棋手加入</strong><span>房间号 {game.room_code}</span><Button size="small" variant="quiet" leadingIcon={<Users />} onClick={() => { setGame(null); setSelectedSquare(null); void refreshGames(); }}>离开房间</Button></div> : null}
            {game.status === "finished" ? (
              <div className={`xiangqi-result ${resultTone(game)}`}>
                <strong>{resultTitle(game)}</strong>
                <span>{FINISH_LABELS[game.finish_reason ?? ""] ?? "棋局结束"}</span>
                <Button size="small" leadingIcon={<RotateCcw />} onClick={() => { setGame(null); void refreshGames(); }}>返回棋局列表</Button>
              </div>
            ) : null}
          </section>

          <aside className="xiangqi-side">
            <section className="xiangqi-room-strip">
              <div><small>房间号</small><strong>{game.room_code}</strong></div>
              <Tooltip content="复制房间号"><Button variant="quiet" leadingIcon={<Copy />} onClick={() => void copyRoomCode()}>复制</Button></Tooltip>
            </section>

            <section className="xiangqi-actions" aria-label="棋局操作">
              {game.status === "waiting" ? <Button size="small" variant="quiet" leadingIcon={<Users />} onClick={() => { setGame(null); setSelectedSquare(null); void refreshGames(); }}>离开房间</Button> : null}
              <Button size="small" variant="quiet" leadingIcon={<History />} onClick={() => setRecordOpen(true)}>对局记录</Button>
              {game.draw_offered_by_opponent ? <>
                <Button size="small" leadingIcon={<Check />} disabled={busy} onClick={() => void drawAction("accept")}>接受和棋</Button>
                <Button size="small" variant="quiet" disabled={busy} onClick={() => void drawAction("decline")}>拒绝</Button>
              </> : game.draw_offered_by_me ? <span className="xiangqi-action-note">和棋请求已发送，等待对手回应</span> : <Button size="small" variant="quiet" leadingIcon={<Handshake />} disabled={busy || game.status !== "active"} onClick={() => void drawAction("offer")}>求和</Button>}
              <Button size="small" variant="quiet" leadingIcon={<Flag />} disabled={busy || game.status !== "active"} onClick={() => setConfirmResign(true)}>认输</Button>
            </section>

            <section className="xiangqi-match-card"><span className="section-kicker">{game.game_type === "ranked" ? "RANKED GAME" : "FRIEND GAME"}</span><h2>{game.game_type === "ranked" ? "天梯积分对局" : "好友练习局 · 免费"}</h2><p>{game.game_type === "ranked" ? `双方各投入 ${profile?.stake ?? 2500} 金币。积分依据双方分差、赛果与定级局数动态结算。` : "好友房不扣金币、不改变段位或战绩。"}</p><div><span><Coins />{game.game_type === "ranked" ? `入场 ${profile?.stake ?? 2500}` : "免费"}</span><span><Trophy />{profile?.rating ?? "--"} · {ratingTitle(profile?.rating ?? 1000)}</span></div></section>
          </aside>
        </div>
      ) : (
        <div className="xiangqi-lobby">
          {profile ? <section className="xiangqi-profile-strip"><div><small>金币余额</small><strong><Coins />{profile.coins.toLocaleString("zh-CN")}</strong></div><div><small>天梯段位 · {profile.rated_games < 20 ? `定级 ${profile.rated_games}/20` : ratingTitle(profile.rating)}</small><strong><Trophy />{profile.rating}</strong></div><div><small>排位战绩</small><strong>{profile.wins} 胜 · {profile.draws} 和 · {profile.losses} 负</strong></div></section> : null}
          <section className="xiangqi-match-panel">
            <div className={`xiangqi-entry-icon ${matching ? "is-searching" : ""}`}><Search /></div>
            <div className="xiangqi-entry-copy"><span className="section-kicker">LIVE MATCHMAKING</span><h2>{matching ? "正在寻找水平相近的对手" : "实时匹配对局"}</h2><p>每人入场 {profile?.stake ?? 2500} 金币；按 Elo 分差匹配并动态结算天梯分。</p></div>
            {matching ? <Button variant="quiet" leadingIcon={<X />} onClick={() => void cancelMatch()}>取消匹配</Button> : <Button leadingIcon={<Swords />} disabled={busy} onClick={() => void startMatch()}>开始匹配</Button>}
          </section>
          <section className="xiangqi-entry-panel">
            <div className="xiangqi-entry-icon"><Swords /></div>
            <div className="xiangqi-entry-copy"><span className="section-kicker">CREATE A TABLE · FREE</span><h2>好友练习局</h2><p>免费创建，双方均不扣金币、不影响排位。你执红先行。</p></div>
            <Button leadingIcon={<Plus />} disabled={busy} onClick={() => void createRoom()}>创建房间</Button>
          </section>
          <section className="xiangqi-entry-panel">
            <div className="xiangqi-entry-icon secondary"><LogIn /></div>
            <form className="xiangqi-join-form" onSubmit={(event) => void joinRoom(event)}>
              <div className="xiangqi-entry-copy"><span className="section-kicker">JOIN A TABLE</span><h2>加入朋友的棋局</h2><p>输入对方分享的房间号，你将执黑后手。</p></div>
              <div className="xiangqi-join-row"><TextField aria-label="房间号" value={joinCode} maxLength={6} autoComplete="off" placeholder="例如 A7K9Q2" onChange={(event) => setJoinCode(event.target.value.replace(/[^a-z0-9]/gi, "").toUpperCase())} /><Button type="submit" leadingIcon={<ChevronRight />} disabled={joining || joinCode.length !== 6}>{joining ? "加入中" : "加入"}</Button></div>
            </form>
          </section>
          <section className="xiangqi-games-panel">
            <div className="xiangqi-section-title"><div><span className="section-kicker">RECENT TABLES</span><h2>我的棋局</h2></div><Button variant="quiet" size="small" leadingIcon={<RotateCcw />} onClick={() => void refreshGames()}>刷新</Button></div>
            {loading ? <div className="xiangqi-empty">正在读取棋局…</div> : games.length ? <div className="xiangqi-game-list">{games.map((item) => <button type="button" key={item.id} className="xiangqi-game-row" onClick={() => chooseGame(item)}><span className={`xiangqi-game-color ${item.my_color}`}>{item.my_color === "red" ? "帅" : "将"}</span><span className="xiangqi-game-main"><strong>{item.host_name} 对 {item.guest_name || "等待对手"}</strong><small>房间 {item.room_code} · {gameTime(item.updated_at)}</small></span><span className={`xiangqi-game-status ${item.status}`}>{item.status === "waiting" ? "待加入" : item.status === "active" ? (item.my_turn ? "轮到你" : "对弈中") : "已结束"}</span><ChevronRight /></button>)}</div> : <div className="xiangqi-empty"><UserRound /><strong>还没有棋局</strong><span>创建一个房间，邀请朋友来下棋。</span></div>}
          </section>
        </div>
      )}

      <Dialog open={confirmResign} title="确认认输" description="认输后本局立即结束，不能撤回。" onClose={() => setConfirmResign(false)} footer={<><Button variant="quiet" onClick={() => setConfirmResign(false)}>继续对弈</Button><Button onClick={() => void resign()} disabled={busy}>确认认输</Button></>}>
        <p className="xiangqi-dialog-copy">如果只是暂时离开，不需要认输；房间会在数天内保留。</p>
      </Dialog>
      <Dialog open={adOfferOpen && Boolean(profile && profile.ads_used_today < profile.ads_limit)} title={game?.status === "finished" ? "本局结束" : "补充对局金币"} description="看完一段激励广告，可领取 2500 金币。每天最多 6 次。" onClose={() => setAdOfferOpen(false)} footer={<><Button variant="quiet" onClick={() => setAdOfferOpen(false)}>稍后再看</Button><Button leadingIcon={<Gift />} onClick={() => void startAd()}>观看广告</Button></>}>
        <div className="xiangqi-offer-summary"><span><Coins /><strong>+2,500</strong><small>金币奖励</small></span><span><Clock3 /><strong>{profile?.ads_limit ?? 6} 次/天</strong><small>今日剩余 {(profile?.ads_limit ?? 6) - (profile?.ads_used_today ?? 0)} 次</small></span></div>
      </Dialog>
      <Dialog open={reliefOpen && Boolean(profile?.can_claim_relief)} title="金币不足" description="当前余额不足以开始一局，可领取一次 6000 金币救济金。最多可领两次。" onClose={() => setReliefOpen(false)} footer={<><Button variant="quiet" onClick={() => setReliefOpen(false)}>暂不领取</Button><Button leadingIcon={<Coins />} onClick={() => void claimRelief()}>领取 6000 金币</Button></>}>
        <div className="xiangqi-relief-note">领取后余额：{((profile?.coins ?? 0) + (profile?.relief_reward ?? 6000)).toLocaleString("zh-CN")} 金币 · 剩余次数 {profile?.relief_remaining ?? 0}</div>
      </Dialog>
      <Dialog open={Boolean(adSession)} title="激励广告" description="演示环境会模拟完整广告时长；正式接入时由广告平台服务端回调确认奖励。" onClose={() => setAdSession(null)} footer={<><Button variant="quiet" onClick={() => setAdSession(null)}>稍后再看</Button><Button disabled={adSeconds > 0} onClick={() => void claimAd()}>{adSeconds > 0 ? `${adSeconds} 秒后领取` : "领取 2500 金币"}</Button></>}>
        <div className="xiangqi-ad-stage"><Gift /><strong>{adSeconds > 0 ? "广告播放中" : "广告播放完成"}</strong><span>请保持页面开启，奖励只由服务端发放。</span><div><i style={{ width: `${Math.max(0, Math.min(100, ((15 - adSeconds) / 15) * 100))}%` }} /></div></div>
      </Dialog>
      {matching ? <div className="xiangqi-match-backdrop" role="dialog" aria-modal="true" aria-label="正在匹配对手"><div className="xiangqi-match-dialog"><button className="xiangqi-match-close" type="button" aria-label="取消匹配" onClick={() => void cancelMatch()}><X /></button><div className="xiangqi-match-orbit"><div className="xiangqi-match-ring" /><Globe2 className="xiangqi-match-globe" /><LoaderCircle className="xiangqi-match-loader" /></div><span className="section-kicker">LIVE MATCHMAKING</span><h2>正在寻找对手</h2><p>为你匹配积分相近的棋手</p><div className="xiangqi-match-stats"><span><small>等待时间</small><strong>{formatDuration(matchElapsed * 1000)}</strong></span><span><small>当前积分</small><strong>{profile?.rating ?? "--"}</strong></span><span><small>入场金币</small><strong>{(profile?.stake ?? 2500).toLocaleString("zh-CN")}</strong></span></div><Button variant="quiet" leadingIcon={<X />} onClick={() => void cancelMatch()}>取消匹配</Button></div></div> : null}
      {recordOpen && game ? <div className="xiangqi-record-backdrop" onMouseDown={(event) => { if (event.target === event.currentTarget) setRecordOpen(false); }}><aside className="xiangqi-record-drawer"><header><div><span className="section-kicker">GAME RECORD</span><h2>对局记录</h2></div><button type="button" aria-label="关闭记录" onClick={() => setRecordOpen(false)}><X /></button></header><div className="xiangqi-record-tabs"><button type="button" className={recordTab === "chat" ? "active" : ""} onClick={() => setRecordTab("chat")}><MessageCircle />聊天记录</button><button type="button" className={recordTab === "moves" ? "active" : ""} onClick={() => setRecordTab("moves")}><History />走棋日志</button></div>{recordTab === "chat" ? <div className="xiangqi-chat-log">{messages.length ? messages.map((message) => <div key={message.id} className={message.mine ? "mine" : ""}><span><strong>{message.mine ? "我" : message.name}</strong><small>{gameTime(message.created_at)}</small></span><p>{message.body}</p></div>) : <div className="xiangqi-history-empty">还没有聊天消息。</div>}</div> : <div className="xiangqi-drawer-moves"><div className="xiangqi-section-title"><StatusBadge tone={game.status === "active" ? "success" : "neutral"}>{game.moves.length} 手</StatusBadge></div><MoveList moves={game.moves} /></div>}</aside></div> : null}
    </main>
  );
}

function Board({ game, selectedSquare, busy, onSelect }: { game: ApiXiangqiGame; selectedSquare: string | null; busy: boolean; onSelect: (square: string) => void }) {
  const flipped = game.my_color === "black";
  const files = flipped ? ["i", "h", "g", "f", "e", "d", "c", "b", "a"] : ["a", "b", "c", "d", "e", "f", "g", "h", "i"];
  const ranks = flipped ? [0, 1, 2, 3, 4, 5, 6, 7, 8, 9] : [9, 8, 7, 6, 5, 4, 3, 2, 1, 0];
  const legalTargets = new Set(selectedSquare ? game.legal_moves[selectedSquare] ?? [] : []);
  const lastMove = game.moves[game.moves.length - 1];
  const checkedKing = game.in_check ? `${game.turn === "red" ? "r" : "b"}K` : "";
  return (
    <div className={`xiangqi-board-shell ${flipped ? "is-flipped" : ""}`}>
      <div className="xiangqi-board" role="grid" aria-label={`中国象棋棋盘，${game.my_color === "red" ? "红方在下" : "黑方在下"}`}>
        <svg className="xiangqi-lines" viewBox="0 0 8 9" preserveAspectRatio="none" aria-hidden="true">
          <g className="xiangqi-grid-lines">
            {Array.from({ length: 9 }, (_, index) => <line key={`v${index}`} x1={index} y1="0" x2={index} y2="9" />)}
            {Array.from({ length: 10 }, (_, index) => <line key={`h${index}`} x1="0" y1={index} x2="8" y2={index} />)}
          </g>
          <g className="xiangqi-grid-lines">
            <line x1="3" y1="0" x2="5" y2="2" /><line x1="5" y1="0" x2="3" y2="2" />
            <line x1="3" y1="7" x2="5" y2="9" /><line x1="5" y1="7" x2="3" y2="9" />
          </g>
          <g className="xiangqi-river-text"><text x="2" y="4.57">楚 河</text><text x="6" y="4.57">漢 界</text></g>
        </svg>
        <div className="xiangqi-points">
          {ranks.flatMap((rank, rankIndex) => files.map((file, fileIndex) => {
            const square = `${file}${rank}`;
            const piece = game.board.squares[square];
            const isSelected = selectedSquare === square;
            const isTarget = legalTargets.has(square);
            const isLast = lastMove && (lastMove.from === square || lastMove.to === square);
            const isChecked = piece === checkedKing;
            return <button type="button" role="gridcell" aria-label={`${square}${piece ? ` ${PIECE_LABELS[piece]}` : ""}`} key={square} className={`xiangqi-point${isSelected ? " is-selected" : ""}${isTarget ? " is-target" : ""}${isLast ? " is-last" : ""}${isChecked ? " is-check" : ""}`} style={{ left: `${fileIndex * 12.5}%`, top: `${rankIndex * (100 / 9)}%` }} disabled={busy || !game.my_turn} onClick={() => onSelect(square)}><span className="xiangqi-hit-target" />{piece ? <span className={`xiangqi-piece ${piece.startsWith("r") ? "red" : "black"}`}><span>{PIECE_LABELS[piece] ?? "棋"}</span></span> : null}</button>;
          }))}
        </div>
      </div>
      <div className="xiangqi-file-labels" aria-hidden="true">{(flipped ? ["9", "8", "7", "6", "5", "4", "3", "2", "1"] : ["1", "2", "3", "4", "5", "6", "7", "8", "9"]).map((label) => <span key={label}>{label}</span>)}</div>
    </div>
  );
}

function MoveList({ moves }: { moves: ApiXiangqiMove[] }) {
  if (!moves.length) return <div className="xiangqi-history-empty">第一步落下后，走棋记录会显示在这里。</div>;
  return <div className="xiangqi-move-list">{moves.map((move, index) => <div className="xiangqi-move-row" key={`${move.from}-${move.to}-${index}`}><span>{move.number}</span><i className={move.color}>{move.color === "red" ? "红" : "黑"}</i><strong>{PIECE_LABELS[move.piece] ?? "棋"}</strong><code>{move.from} → {move.to}</code>{move.captured ? <small>吃 {PIECE_LABELS[move.captured]}</small> : null}</div>)}</div>;
}

function resultTitle(game: ApiXiangqiGame) {
  if (!game.winner_color) return "本局和棋";
  return game.winner_color === game.my_color ? "你赢了" : "对手获胜";
}

function resultTone(game: ApiXiangqiGame) {
  if (!game.winner_color) return "is-draw";
  return game.winner_color === game.my_color ? "is-win" : "is-loss";
}
