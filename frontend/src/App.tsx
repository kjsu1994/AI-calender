import { useEffect, useRef, useState, type FormEvent } from 'react'
import { AudioLines, Mic, Square, ArrowUp, Plus, ChevronLeft, ChevronRight, CalendarDays, List, Settings2, RefreshCw, LogOut, Volume2, X, Check, Clock3, MapPin, ArrowUpRight, LoaderCircle, AlertCircle, Sparkles, Pencil, Trash2 } from 'lucide-react'
import { api, dayKey, dateFromKey, moveDay, onDay, requestId, timeLabel, type CalendarEvent, type Command, type EventData, type Session, type ActivityRecord } from './api'
import { useRecorder } from './useRecorder'
import ActivityPanel from './ActivityPanel'
import PasswordSignIn from './PasswordSignIn'
import { DeviceCode, DeviceSignIn, getPublicHttpsOrigin, isCanonicalOrigin } from './DeviceLink'

const weekdays = ['일', '월', '화', '수', '목', '금', '토']
const syncNames: Record<string, string> = { LOCAL: '샘플 일정', SYNCED: '동기화 완료', PENDING: '동기화 대기', ERROR: '재시도 필요', CONFLICT: '변경 충돌', DEMO: '샘플 모드', REAUTH: '재연동 필요', DISCONNECTED: '연결 대기', ATTENTION: '확인 필요' }
const syncErrorMessages: Record<string, string> = {
  ERROR: 'Google Calendar와 일정을 동기화하지 못했어요. 잠시 후 다시 시도해 주세요.',
  ATTENTION: '동기화를 마치지 못한 일정이 있어요. 아래 동기화 상태에서 변경 내용을 확인해 주세요.',
  REAUTH: 'Google Calendar에 접근하려면 계정을 다시 연결해야 해요.',
}
function lastSyncLabel(value?: string) {
  const date = value ? new Date(value) : null
  return date && Number.isFinite(date.getTime()) ? new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', month: 'long', day: 'numeric', hour: 'numeric', minute: '2-digit' }).format(date) : '완료 기록 없음'
}
function say(text: string) {
  if (!window.speechSynthesis) return
  window.speechSynthesis.cancel()
  const utterance = new SpeechSynthesisUtterance(text); utterance.lang = 'ko-KR'; utterance.rate = 1
  window.speechSynthesis.speak(utterance)
}
function monthRange(day: string) {
  const start = day.slice(0, 7) + '-01'
  const d = dateFromKey(start); d.setUTCMonth(d.getUTCMonth() + 1)
  return { start, end: dayKey(d) }
}
function moveMonth(day: string, delta: number) { const d = dateFromKey(day.slice(0, 7) + '-01'); d.setUTCMonth(d.getUTCMonth() + delta); return dayKey(d) }
function accountIdentity(session: Session | null) { return session?.user ? session.user.id || `${session.user.demo}:${session.user.email}` : null }
function EventSummary({ data }: { data: EventData }) {
  return <div className="event-summary"><strong>{data.deleted ? '삭제됨' : data.title}</strong>{!data.deleted && <><span>{timeLabel(data)}</span><span>종료 · {data.allDay ? data.end + ' (해당 날짜 미포함)' : timeLabel({ ...data, start: data.end })}</span>{data.location && <span>{data.location}</span>}{data.description && <span className="summary-content">{data.description}</span>}</>}</div>
}

export default function App() {
  const today = dayKey(new Date())
  const [session, setSession] = useState<Session | null>(null)
  const [date, setDate] = useState(today)
  const [events, setEvents] = useState<CalendarEvent[]>([])
  const [records, setRecords] = useState<ActivityRecord[]>([])
  const [issues, setIssues] = useState<CalendarEvent[]>([])
  const [view, setView] = useState<'day' | 'month' | 'records'>('day')
  const [text, setText] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [command, setCommand] = useState<Command | null>(null)
  const [editing, setEditing] = useState<CalendarEvent | 'new' | null>(null)
  const [settings, setSettings] = useState(false)
  const [calendars, setCalendars] = useState<{ id: string; name: string }[]>([])
  const [selectedCalendar, setSelectedCalendar] = useState('')
  const [readAloud, setReadAloud] = useState(true)
  const range = monthRange(date)
  const requestRef = useRef(false)
  const reloadRef = useRef(0)
  const accountRef = useRef<string | null>(null)
  const pendingSync = issues.some(issue => issue.syncState === 'PENDING')
  async function reload() {
    const seq = ++reloadRef.current
    const s = await api<Session>('/session')
    if (seq !== reloadRef.current) return
    const identity = accountIdentity(s)
    if (identity !== accountRef.current) {
      accountRef.current = identity
      setEvents([]); setRecords([]); setIssues([]); setCommand(null); setText(''); setError('')
      setEditing(null); setSettings(false); setCalendars([]); setSelectedCalendar(''); setView('day')
      recorder.cancel(); window.speechSynthesis?.cancel()
    }
    setSession(s)
    if (s.user) {
      const [items, notes, state] = await Promise.all([s.calendarId ? api<CalendarEvent[]>(`/events?from=${range.start}&to=${range.end}`) : Promise.resolve([]), api<ActivityRecord[]>(`/records?from=${range.start}&to=${range.end}`), api<{ issues: CalendarEvent[] }>('/sync')])
      if (seq === reloadRef.current) { setEvents(items); setRecords(notes); setIssues(state.issues) }
    }
  }
  useEffect(() => {
    let active = true
    reload().catch(e => { if (active) setError(e.message) })
    return () => { active = false; ++reloadRef.current }
  }, [range.start])
  useEffect(() => {
    let polling = false
    const interval = setInterval(async () => {
      if (document.hidden || polling || requestRef.current) return
      polling = true
      try { await reload() } catch { /* Keep existing data until the next successful refresh. */ }
      finally { polling = false }
    }, pendingSync ? 2000 : 15000)
    return () => clearInterval(interval)
  }, [range.start, pendingSync])
  useEffect(() => {
    if (new URLSearchParams(location.search).has('authError')) {
      setError('Google 로그인에 실패했습니다. 허용 계정, OAuth 동의와 콜백 주소를 확인해 주세요.')
      history.replaceState(null, '', '/')
    }
  }, [])
  async function run(task: () => Promise<void>) {
    if (requestRef.current) return
    const identity = accountRef.current
    requestRef.current = true; setBusy(true); setError('')
    try { await task() } catch (e) { if (identity === accountRef.current) setError(e instanceof Error ? e.message : '요청에 실패했습니다.') }
    finally { requestRef.current = false; setBusy(false) }
  }
  function receive(result: Command) { if (accountIdentity(session) !== accountRef.current) return; setCommand(result); if (result.record || result.records || result.kind === 'record') setView('records'); if (readAloud) say(result.message) }
  const parentId = command && ['NEEDS_INPUT', 'CONFIRMATION'].includes(command.status) ? command.commandId : undefined
  const recorder = useRecorder(blob => {
    void run(async () => {
      const form = new FormData(); form.append('requestId', requestId()); form.append('audio', blob, 'recording.audio')
      if (parentId) form.append('parentId', parentId)
      receive(await api<Command>('/commands/audio', 'POST', form)); await reload()
    })
  }, setError)
  async function submit(e: FormEvent) {
    e.preventDefault(); if (!text.trim()) return
    await run(async () => { const result = await api<Command>('/commands/text', 'POST', { requestId: requestId(), text, parentId }); setText(''); receive(result); await reload() })
  }
  async function openSettings() {
    setSettings(true)
    if (session?.user) {
      setSelectedCalendar(session.calendarId || '')
      if (session.user.demo || session.googleConnected !== false) await run(async () => {
        const options = await api<{ id: string; name: string }[]>('/calendars')
        if (accountIdentity(session) === accountRef.current) setCalendars(options)
      })
      else setCalendars([])
    }
  }
  async function retrySync() {
    await run(async () => {
      try { await api('/sync', 'POST') }
      finally { await reload() }
    })
  }
  const active = events.filter(e => onDay(e, date))
  const weekStart = moveDay(date, -dateFromKey(date).getUTCDay())
  const week = Array.from({ length: 7 }, (_, i) => moveDay(weekStart, i))
  const ready = !!session?.user && !!session.calendarId
  const voiceReady = !!session?.user
  const googleConnected = !!session?.user && (session.user.demo || session.googleConnected !== false)
  const canonicalOrigin = !!session && isCanonicalOrigin(session.publicBaseUrl)
  const publicHttpsOrigin = session ? getPublicHttpsOrigin(session.publicBaseUrl) : null
  const secureContext = window.isSecureContext
  const syncProblem = !!session?.user && !session.user.demo && !!syncErrorMessages[session.syncStatus || '']
  const syncMessage = syncProblem ? session?.syncError?.trim() || syncErrorMessages[session?.syncStatus || 'ERROR'] : ''
  const displayedSyncStatus = session?.syncStatus === 'SYNCED' && pendingSync ? 'PENDING' : session?.syncStatus || 'DISCONNECTED'
  const disabled = busy || recorder.recording
  const fullDate = new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', month: 'long', day: 'numeric', weekday: 'long' }).format(dateFromKey(date))

  return <div className="app-shell">
    <aside className="sidebar">
      <a className="brand" href="/" aria-label="데이보이스 홈"><span className="brand-symbol"><img src="/brand/dayvoice-mark.png?v=20260925" alt="" width={37} height={37} /></span><span>dayvoice<span className="brand-dot">.</span></span></a>
      <div className="workspace-label">MY WORKSPACE</div>
      <nav aria-label="주 메뉴">
        <button className={view === 'day' ? 'nav-item selected' : 'nav-item'} onClick={() => setView('day')}><List size={19} />나의 일정<span className="nav-count">{events.filter(e => onDay(e, today)).length}</span></button>
        <button className={view === 'month' ? 'nav-item selected' : 'nav-item'} onClick={() => setView('month')}><CalendarDays size={19} />월간 캘린더</button>
        <button className={view === 'records' ? 'nav-item selected' : 'nav-item'} onClick={() => setView('records')}><Pencil size={19} />시간별 기록</button>
        <button className="nav-item" onClick={openSettings}><Settings2 size={19} />연결 및 설정</button>
      </nav>
      <div className="sidebar-note"><div className="tiny-orbit"><Sparkles size={18} /></div><strong>생각은 가볍게,<br />하루는 선명하게.</strong><p>기억해야 할 일은 맡기고<br />지금 이 순간에 집중하세요.</p></div>
      <div className="sidebar-bottom"><span className="status-dot" /><span>{session?.user?.demo ? '샘플 워크스페이스' : session?.calendarName || '나만의 일정 공간'}</span></div>
    </aside>

    <main className="main">
      <header className="topbar"><div className="topbar-location"><a className="mobile-brand" href="/" aria-label="데이보이스 홈"><img src="/brand/dayvoice-mark.png?v=20260925" alt="" width={34} height={34} /></a><span className="breadcrumb">나의 공간 <span>/</span> {view === 'records' ? '시간별 기록' : view === 'day' ? '나의 일정' : '월간 캘린더'}</span></div><div className="top-actions"><span className="today-top">{today.replaceAll('-', '. ')}</span><button className="icon-button mobile-settings" aria-label="설정 열기" onClick={openSettings}><Settings2 size={20} /></button><button className="avatar" onClick={openSettings} aria-label="계정 설정">{session?.user?.name?.[0] || 'D'}</button></div></header>
      <div className="content">
        <div className="page-heading"><div><div className="eyebrow">A LITTLE MORE ROOM IN YOUR DAY</div><h1>나의 하루를, 말하다<span>.</span></h1><p>해야 할 일부터 소중한 약속까지. 말 한마디로 정리해 보세요.</p></div><span className="date-pill"><span className="status-dot" />{session?.user?.demo ? '샘플 모드' : syncNames[displayedSyncStatus]}</span></div>

        {error && error.trim() !== syncMessage && <div className="error-banner" role="alert"><AlertCircle size={20} /><span>{error}</span><button className="icon-button" aria-label="오류 닫기" onClick={() => setError('')}><X size={17} /></button></div>}
        {syncProblem && <section className="sync-error-banner" role="alert" aria-label="Google Calendar 동기화 오류">
          <AlertCircle size={21} />
          <div className="sync-error-content"><h2>{session?.syncStatus === 'REAUTH' ? 'Google 계정을 다시 연결해 주세요' : session?.syncStatus === 'ATTENTION' ? '일부 일정의 동기화를 확인해 주세요' : '일정을 동기화하지 못했어요'}</h2><p>{syncMessage}</p><span className="last-sync"><Clock3 size={13} />마지막 동기화 · {lastSyncLabel(session?.lastSync)}</span>
            {session?.syncStatus === 'REAUTH' && !canonicalOrigin && <p>{publicHttpsOrigin ? 'HTTPS 주소' : '서버 PC'}에서 <strong className="server-address">{session.publicBaseUrl}</strong>을 열고 Google 계정을 다시 연결해 주세요.</p>}
          </div>
          <div className="sync-error-actions">{session?.syncStatus === 'REAUTH' && canonicalOrigin && <a className="primary-button" href="/api/auth/google">Google 계정 다시 연결</a>}<button className="secondary-button" disabled={!ready || disabled} onClick={retrySync}><RefreshCw size={15} className={busy ? 'spin' : ''} />{busy ? '처리 중' : '다시 동기화'}</button></div>
        </section>}
        {!secureContext && <div className="microphone-notice" role="note"><Mic size={19} /><p>이 HTTP 주소에서는 PC와 휴대폰 모두 마이크를 사용할 수 없어요. 음성 입력에는 HTTPS 연결이 필요해요. 서버를 실행 중인 PC에서는 localhost 주소로 접속하면 마이크를 사용할 수 있어요.</p></div>}

        {!session && !error && <div className="loading"><LoaderCircle className="spin" />공간을 준비하고 있어요.</div>}
        {session && !session.user && (!session.demoMode && !canonicalOrigin ? <DeviceSignIn publicBaseUrl={session.publicBaseUrl} connected={reload} /> : !session.demoMode && session.passwordLoginConfigured ? <PasswordSignIn googleConfigured={session.googleConfigured} connected={reload} /> : <section className="welcome-panel"><div><span className="eyebrow">WELCOME TO YOUR DAY</span><h2>흩어진 일정을 한곳에.</h2><p>Google Calendar를 연결하고<br />일정과 기록을 한곳에서 관리하세요.</p></div>{session.demoMode ? <button className="primary-button" disabled={busy} onClick={() => run(async () => { await api('/auth/demo', 'POST'); await reload() })}>샘플로 시작하기 <ArrowUpRight size={18} /></button> : <a className="primary-button" href="/api/auth/google">Google 계정으로 시작 <ArrowUpRight size={18} /></a>}</section>)}
        {session?.user && !session.calendarId && <div className="connection-banner"><CalendarDays /><span>{googleConnected ? '일정을 관리할 Google 캘린더를 선택해 주세요.' : 'Google Calendar를 연결하면 일정도 함께 관리할 수 있어요. 시간별 기록은 바로 사용할 수 있어요.'}</span><button className="primary-button" onClick={openSettings}>{googleConnected ? '캘린더 선택' : 'Google Calendar 연결'}</button></div>}

        <section className={'voice-panel ' + (recorder.recording ? 'is-recording' : '')} aria-label="음성 일정 입력">
          <div className="voice-decoration" aria-hidden="true"><div /><div /><div /></div>
          <div className="voice-header"><span className="small-label"><AudioLines size={15} /> VOICE ASSISTANT</span><span className="voice-state"><span className="status-dot" />{recorder.recording ? `녹음 중 · ${recorder.seconds}초` : busy ? '처리 중' : '당신의 이야기를 기다려요'}</span></div>
          <div className="voice-main"><button className={'mic-button ' + (recorder.recording ? 'recording' : '')} onClick={recorder.toggle} disabled={!voiceReady || busy || !secureContext} aria-label={recorder.recording ? '녹음 종료' : '음성 입력 시작'}>{busy ? <LoaderCircle size={29} className="spin" /> : recorder.recording ? <Square size={25} fill="currentColor" /> : <Mic size={30} />}</button><div><h2>{recorder.recording ? '듣고 있어요. 편하게 말해 주세요.' : busy ? '일정을 정리하고 있어요.' : '어떤 내용을 기억해 둘까요?'}</h2><p>{recorder.recording ? '말을 마치면 정지 버튼을 눌러 주세요. 최대 60초까지 녹음해요.' : secureContext ? '마이크를 누르고 말해 주세요. 나머지는 제가 정리할게요.' : '아래 입력창에 일정이나 기록을 적어 주세요.'}</p></div></div>
          <div className="suggestions">{['내일 오후 3시에 치과 예약', '이번 주 일정 알려 줘', '오늘 12~13시 고객 미팅, 견적 수정 요청받음 기록해 줘'].map(t => <button key={t} disabled={!voiceReady || disabled} onClick={() => setText(t)}>“{t}” <ArrowUpRight size={13} /></button>)}</div>
          <form className="text-input" onSubmit={submit}><input aria-label="일정 요청" value={text} onChange={e => setText(e.target.value)} placeholder={parentId ? '추가 질문에 답하거나, 확인할 내용을 말해 주세요' : '직접 입력해도 좋아요. 예: 내일 오후 3시에 치과 예약해 줘'} maxLength={4000} disabled={!voiceReady || disabled} /><button type="submit" aria-label="요청 보내기" disabled={!voiceReady || disabled || !text.trim()}><ArrowUp size={18} /></button></form>
          <div className="voice-footer"><span>등록은 바로 · 수정과 삭제는 확인 후</span><button onClick={() => { setReadAloud(!readAloud); window.speechSynthesis?.cancel() }} aria-pressed={readAloud}><Volume2 size={14} />음성 안내 {readAloud ? '켜짐' : '꺼짐'}</button></div>
        </section>

        {command && <section className={'command-card ' + (command.status === 'CONFIRMATION' ? 'confirm' : '')} aria-live="polite">
          <div className="command-top"><span className="small-label"><Sparkles size={15} /> {command.status === 'CONFIRMATION' ? '변경 확인' : command.status === 'NEEDS_INPUT' ? '조금 더 알려 주세요' : '처리 결과'}</span><div><button className="icon-button" aria-label="결과 읽어주기" onClick={() => say(command.message)}><Volume2 size={17} /></button><button className="icon-button" disabled={busy} aria-label="결과 닫기" onClick={() => run(async () => { if (command.status === 'CONFIRMATION') await api(`/commands/${command.commandId}/confirm`, 'POST', { confirm: false }); setCommand(null) })}><X size={17} /></button></div></div>
          {command.transcript && <p className="transcript">“{command.transcript}”</p>}<p className="command-message">{command.message}</p>
          {command.status === 'CONFIRMATION' && <><div className="change-preview"><div><span className="muted">변경 전</span>{command.before && <EventSummary data={command.before} />}</div><span className="change-arrow">→</span><div><span className="muted">변경 후</span>{command.after && <EventSummary data={command.after} />}</div></div><div className="button-row"><button className="secondary-button" disabled={busy} onClick={() => run(async () => receive(await api(`/commands/${command.commandId}/confirm`, 'POST', { confirm: false })))}>취소</button><button className="primary-button" disabled={busy} onClick={() => run(async () => { receive(await api(`/commands/${command.commandId}/confirm`, 'POST', { confirm: true })); await reload() })}><Check size={16} />확인하고 실행</button></div></>}
          {command.status === 'DONE' && command.event && <EventSummary data={{ ...command.event.data, deleted: command.event.deleted }} />}
          {command.record && <EventSummary data={{ ...command.record.data, deleted: command.record.deleted }} />}{command.records?.map(r => <div className="query-item" key={r.id}><EventSummary data={r.data} />{r.eventTitle && <p className="helper">연결 일정 · {r.eventTitle}</p>}</div>)}
          {command.events?.map(e => <div className="query-item" key={e.id}><EventSummary data={e.data} /></div>)}
        </section>}

        <div className="workspace-tabs" aria-label="일정과 기록 선택"><button className={view !== 'records' ? 'active' : ''} onClick={() => setView('day')}><CalendarDays size={16} />일정</button><button className={view === 'records' ? 'active' : ''} onClick={() => setView('records')}><Pencil size={16} />시간별 기록<span>{records.filter(r => onDay(r, date)).length}</span></button></div>
        {view === 'records' ? <ActivityPanel date={date} setDate={setDate} records={records} events={events} enabled={voiceReady} busy={disabled} error={error} save={async (record, data, remove) => {
          let success = false
          await run(async () => {
            const result = await api<Command>(record ? '/records/' + record.id : '/records', remove ? 'DELETE' : record ? 'PUT' : 'POST', { requestId: requestId(), version: record?.version || 0, record: data })
            receive(result); await reload(); success = true
          })
          return success
        }} /> : <section className="schedule-section">
          <div className="section-heading"><div><h2>{view === 'month' ? `${date.slice(0, 4)}년 ${Number(date.slice(5, 7))}월` : date === today ? '오늘의 일정' : '나의 일정'}<span className="count-badge">{view === 'month' ? events.length : active.length}</span></h2><p>{view === 'month' ? '한 달의 흐름을 한눈에 살펴보세요.' : fullDate}</p></div><div className="schedule-actions"><div className="view-switch"><button className={view === 'day' ? 'active' : ''} aria-label="목록 보기" onClick={() => setView('day')}><List size={18} /></button><button className={view === 'month' ? 'active' : ''} aria-label="월간 보기" onClick={() => setView('month')}><CalendarDays size={18} /></button></div><button className="primary-button add-button" disabled={!ready || disabled} onClick={() => setEditing('new')}><Plus size={16} />일정 추가</button></div></div>
          <div className="date-navigation"><button className="icon-button" aria-label="이전 기간" onClick={() => setDate(view === 'day' ? moveDay(date, -7) : moveMonth(date, -1))}><ChevronLeft size={18} /></button><span>{date.slice(0, 4)}년 {Number(date.slice(5, 7))}월</span><button className="icon-button" aria-label="다음 기간" onClick={() => setDate(view === 'day' ? moveDay(date, 7) : moveMonth(date, 1))}><ChevronRight size={18} /></button><button className="today-button" onClick={() => setDate(today)}>오늘</button></div>
          {view === 'day' ? <><div className="week-strip">{week.map(d => <button key={d} className={(d === date ? 'chosen ' : '') + (d === today ? 'is-today' : '')} onClick={() => setDate(d)}><span>{weekdays[dateFromKey(d).getUTCDay()]}</span><strong>{Number(d.slice(-2))}</strong><i className={events.some(e => onDay(e, d)) ? 'has-event' : ''} /></button>)}</div><div className="event-list">
            {active.length === 0 && <div className="empty-state"><CalendarDays size={29} /><h3>{syncProblem ? '일정의 동기화가 필요해요' : ready ? '아직 정해진 일정이 없어요' : '당신의 하루를 기다리고 있어요'}</h3><p>{syncProblem ? '위의 동기화 안내를 확인하고 다시 시도해 주세요.' : ready ? '새로운 계획을 말하거나 직접 추가해 보세요.' : '로그인하면 일정을 이곳에서 볼 수 있어요.'}</p></div>}
            {active.map((event, i) => <article className={'event-row color-' + (i % 3)} key={event.id}><div className="event-time"><strong>{event.data.allDay ? '종일' : new Intl.DateTimeFormat('ko-KR', { hour: '2-digit', minute: '2-digit', hour12: false, timeZone: 'Asia/Seoul' }).format(new Date(event.data.start))}</strong><span>{event.data.allDay ? '' : new Intl.DateTimeFormat('ko-KR', { hour: '2-digit', minute: '2-digit', hour12: false, timeZone: 'Asia/Seoul' }).format(new Date(event.data.end))}</span></div><div className="event-line" /><div className="event-body"><div className="event-title"><h3>{event.data.title}</h3>{event.recurring && <span className="recurring-tag">반복 · 조회 전용</span>}</div><div className="event-meta">{event.data.location && <span><MapPin size={13} />{event.data.location}</span>}<span className={'sync-label ' + event.syncState.toLowerCase()}><span className="status-dot" />{syncNames[event.syncState]}</span></div>{event.data.description && <p className="event-description">{event.data.description}</p>}</div><button className="icon-button" aria-label={`${event.data.title} 상세 보기`} disabled={disabled} onClick={() => setEditing(event)}><ArrowUpRight size={18} /></button></article>)}
          </div></> : <MonthGrid date={date} today={today} events={events} onSelect={d => { setDate(d); setView('day') }} />}
        </section>}
        {issues.length > 0 && <section className="issues-section"><h3><RefreshCw size={17} />동기화 상태 <span>{issues.length}</span></h3>{issues.map(e => <div className="issue" key={e.id}><div><strong>{e.data.title}{e.deleted ? ' · 삭제 요청' : ''}</strong><span>{syncNames[e.syncState]}</span></div>{e.syncState === 'CONFLICT' && <><div className="change-preview"><div><small>내 변경</small><EventSummary data={{ ...e.data, deleted: e.deleted }} /></div><div><small>Google 변경</small>{e.remote && <EventSummary data={e.remote} />}</div></div><div className="button-row"><button className="secondary-button" disabled={busy} onClick={() => run(async () => { await api(`/events/${e.id}/resolve`, 'POST', { choice: 'remote', version: e.version }); await reload() })}>Google 내용 선택</button><button className="primary-button" disabled={busy} onClick={() => run(async () => { await api(`/events/${e.id}/resolve`, 'POST', { choice: 'local', version: e.version }); await reload() })}>내 변경 적용</button></div></>}</div>)}</section>}
        <footer className="page-footer"><span><AudioLines size={15} />조금 더 여유로운 하루, dayvoice</span><button disabled={!ready || busy || recorder.recording} onClick={retrySync}><RefreshCw size={13} />{session?.user?.demo ? '샘플 일정 새로고침' : 'Google Calendar 동기화'}</button></footer>
      </div>
    </main>
    {settings && <Modal title="연결 및 설정" close={() => setSettings(false)}><div className="settings-content">
      <p className="muted">{session?.user?.email || '아직 로그인하지 않았어요.'}</p>
      <div className="setting-row"><span>Gemini 연결</span><span className={session?.geminiConfigured ? 'good' : 'muted'}>{session?.geminiConfigured ? 'API 키 설정됨' : '.env에 키 입력 필요'}</span></div>
      {!session?.geminiConfigured && <p className="helper">루트 .env의 GEMINI_API_KEY를 입력하고 backend를 재시작해 주세요.</p>}
      <div className="setting-row"><span>음성 안내</span><button className="secondary-button" onClick={() => setReadAloud(!readAloud)}>{readAloud ? '켜짐' : '꺼짐'}</button></div>
      {session?.user && <>
        {googleConnected ? <><label className="field">사용할 캘린더<select value={selectedCalendar} onChange={e => setSelectedCalendar(e.target.value)} disabled={!!session.calendarId}><option value="">캘린더를 선택하세요</option>{calendars.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}</select></label>
        {!session.calendarId && <button className="primary-button" disabled={!selectedCalendar || busy} onClick={() => run(async () => { await api('/calendars/select', 'POST', { calendarId: selectedCalendar }); await reload(); setSettings(false); await api('/sync', 'POST'); await reload() })}>이 캘린더 연결</button>}
        <p className="helper">반복 일정은 지난 30일부터 앞으로 1년까지 조회해요. 변경은 Google Calendar에서 진행해 주세요.</p></> : <div className="google-connection-prompt"><h3><CalendarDays size={17} />Google Calendar 연결</h3><p className="helper">Google 계정을 한 번 연결하면 기존 일정과 새 일정을 함께 관리할 수 있어요.</p>{canonicalOrigin ? <a className="primary-button" href="/api/auth/google">Google Calendar 연결 <ArrowUpRight size={16} /></a> : publicHttpsOrigin ? <a className="primary-button" href={publicHttpsOrigin}>HTTPS 주소로 접속 <ArrowUpRight size={16} /></a> : <p className="helper">서버 PC에서 <strong className="server-address">{session.publicBaseUrl}</strong>에 로그인한 뒤 Google Calendar를 연결해 주세요.</p>}</div>}
        {!session.user.demo && <>
          {publicHttpsOrigin ? <p className="helper">다른 기기에서도 <strong className="server-address">{publicHttpsOrigin}</strong>에 접속해 로그인하면 같은 일정과 기록을 사용할 수 있어요.</p> : <DeviceCode key={session.user.email} />}
          {googleConnected && (canonicalOrigin ? <a className="secondary-button" href="/api/auth/google">Google 계정 다시 연결</a> : publicHttpsOrigin ? <a className="secondary-button" href={publicHttpsOrigin}>HTTPS 주소로 접속 <ArrowUpRight size={16} /></a> : <p className="helper">Google 계정을 다시 연결하려면 서버 PC에서 <strong className="server-address">{session.publicBaseUrl}</strong>을 열어 주세요.</p>)}
        </>}
        <button className="logout-button" disabled={busy} onClick={() => run(async () => { await api('/auth/logout', 'POST'); setSession(null); setEvents([]); setRecords([]); setIssues([]); setCommand(null); setSettings(false); await reload() })}><LogOut size={16} />로그아웃</button>
      </>}
        {!secureContext && <div className="local-note"><Clock3 size={16} /><p>PC와 휴대폰 모두 IP 주소에서 음성을 쓰려면 HTTPS 연결이 필요해요. 서버를 실행 중인 PC에서는 localhost 주소로 접속해 음성을 사용할 수 있어요.</p></div>}
    </div></Modal>}
    {editing && <EventEditor event={editing} date={date} busy={busy} close={() => setEditing(null)} save={(data, remove) => run(async () => {
      const existing = editing !== 'new' ? editing : null
      const result = await api<Command>(existing ? `/events/${existing.id}` : '/events', remove ? 'DELETE' : existing ? 'PUT' : 'POST', { requestId: requestId(), version: existing?.version || 0, event: data })
      setEditing(null); receive(result); await reload()
    })} />}
  </div>
}

function MonthGrid({ date, today, events, onSelect }: { date: string; today: string; events: CalendarEvent[]; onSelect: (day: string) => void }) {
  const first = date.slice(0, 7) + '-01', offset = dateFromKey(first).getUTCDay()
  return <div className="month-grid">{weekdays.map(d => <div className="weekday" key={d}>{d}</div>)}{Array.from({ length: 42 }, (_, i) => moveDay(first, i - offset)).map(d => <button key={d} className={(d.slice(0, 7) !== date.slice(0, 7) ? 'outside ' : '') + (d === today ? 'is-today' : '')} onClick={() => onSelect(d)}><span>{Number(d.slice(-2))}</span>{events.filter(e => onDay(e, d)).slice(0, 2).map(e => <small key={e.id}>{e.data.title}</small>)}{events.filter(e => onDay(e, d)).length > 2 && <small>+ 더 보기</small>}</button>)}</div>
}

function Modal({ title, close, children }: { title: string; close: () => void; children: React.ReactNode }) {
  const ref = useRef<HTMLDialogElement>(null)
  useEffect(() => { ref.current?.showModal(); return () => ref.current?.close() }, [])
  return <dialog ref={ref} className="modal" onCancel={e => { e.preventDefault(); close() }} onClick={e => { if (e.target === ref.current) close() }}><div className="modal-heading"><h2>{title}</h2><button className="icon-button" aria-label="닫기" onClick={close}><X size={20} /></button></div>{children}</dialog>
}

function localValue(iso: string) {
  const d = new Date(iso)
  return dayKey(d) + 'T' + new Intl.DateTimeFormat('en-GB', { timeZone: 'Asia/Seoul', hour: '2-digit', minute: '2-digit', hour12: false }).format(d)
}
function EventEditor({ event, date, busy, close, save }: { event: CalendarEvent | 'new'; date: string; busy: boolean; close: () => void; save: (data: EventData, remove?: boolean) => void }) {
  const existing = event === 'new' ? null : event
  const [title, setTitle] = useState(existing?.data.title || '')
  const [allDay, setAllDay] = useState(existing?.data.allDay || false)
  const [start, setStart] = useState(existing ? existing.data.allDay ? existing.data.start : localValue(existing.data.start) : date + 'T09:00')
  const [end, setEnd] = useState(existing ? existing.data.allDay ? existing.data.end : localValue(existing.data.end) : date + 'T10:00')
  const [location, setLocation] = useState(existing?.data.location || '')
  const [description, setDescription] = useState(existing?.data.description || '')
  const readOnly = existing?.recurring || existing?.syncState === 'CONFLICT'
  const data = () => ({ title, allDay, start: allDay ? start.slice(0, 10) : start + ':00+09:00', end: allDay ? end.slice(0, 10) : end + ':00+09:00', location, description })
  return <Modal title={existing ? '일정 상세' : '새로운 일정'} close={close}><form className="event-form" onSubmit={e => { e.preventDefault(); save(data()) }}><label className="field">일정 이름<input required autoFocus value={title} onChange={e => setTitle(e.target.value)} maxLength={200} disabled={readOnly} placeholder="어떤 계획이 있나요?" /></label><label className="checkbox-field"><input type="checkbox" checked={allDay} disabled={readOnly} onChange={e => { const value = e.target.checked; setAllDay(value); if (value) { setStart(start.slice(0, 10)); setEnd(moveDay(start.slice(0, 10), 1)) } else { setStart(start.slice(0, 10) + 'T09:00'); setEnd(start.slice(0, 10) + 'T10:00') } }} />종일 일정</label><div className="form-dates"><label className="field">시작<input type={allDay ? 'date' : 'datetime-local'} required disabled={readOnly} value={start} onChange={e => setStart(e.target.value)} /></label><label className="field">종료{allDay ? ' (해당 날짜 미포함)' : ''}<input type={allDay ? 'date' : 'datetime-local'} required disabled={readOnly} value={end} onChange={e => setEnd(e.target.value)} /></label></div><p className="helper">시간대 · 대한민국 서울 (UTC+9)</p><label className="field">장소<input value={location} disabled={readOnly} onChange={e => setLocation(e.target.value)} maxLength={500} placeholder="장소를 입력하세요" /></label><label className="field">메모<textarea value={description} disabled={readOnly} onChange={e => setDescription(e.target.value)} maxLength={5000} rows={3} placeholder="기억해 둘 내용이 있나요?" /></label>{readOnly ? <p className="helper">{existing?.recurring ? '반복 일정은 Google Calendar에서 변경해 주세요.' : '먼저 동기화 충돌을 해결해 주세요.'}</p> : <div className="editor-actions">{existing && <button type="button" className="danger-button" disabled={busy} onClick={() => save(data(), true)}><Trash2 size={16} />삭제</button>}<button type="submit" className="primary-button" disabled={busy}>{existing ? <Pencil size={15} /> : <Plus size={15} />}{existing ? '변경 내용 확인' : '일정 저장'}</button></div>}</form></Modal>
}
