import { useEffect, useRef, useState } from 'react'
import { Plus, ChevronLeft, ChevronRight, NotebookPen, Link2, Pencil, Trash2, X } from 'lucide-react'
import { dayKey, moveDay, onDay, type ActivityRecord, type CalendarEvent, type RecordInput } from './api'

type Props = {
  date: string; setDate: (date: string) => void; records: ActivityRecord[]; events: CalendarEvent[];
  enabled: boolean; busy: boolean; error: string;
  save: (record: ActivityRecord | null, data: RecordInput, remove: boolean) => Promise<boolean>;
}
function clock(iso: string) { return new Intl.DateTimeFormat('en-GB', { timeZone: 'Asia/Seoul', hour: '2-digit', minute: '2-digit' }).format(new Date(iso)) }
function localValue(iso: string) { return dayKey(new Date(iso)) + 'T' + clock(iso) }

export default function ActivityPanel({ date, setDate, records, events, enabled, busy, error, save }: Props) {
  const [editing, setEditing] = useState<ActivityRecord | 'new' | null>(null)
  const selected = records.filter(r => onDay(r, date))
  return <section className="schedule-section activity-section">
    <div className="section-heading"><div><h2>시간별 활동 기록<span className="count-badge">{selected.length}</span></h2><p>실제로 한 일과 기억할 내용을 시간별로 남겨요.</p></div><button className="primary-button add-button" disabled={!enabled || busy} onClick={() => setEditing('new')}><Plus size={16} />기록 추가</button></div>
    <div className="date-navigation"><button className="icon-button" aria-label="기록 이전 날짜" onClick={() => setDate(moveDay(date, -1))}><ChevronLeft size={18} /></button><label className="record-date"><input type="date" aria-label="기록 날짜" value={date} onChange={e => { if (e.target.value) setDate(e.target.value) }} /></label><button className="icon-button" aria-label="기록 다음 날짜" onClick={() => setDate(moveDay(date, 1))}><ChevronRight size={18} /></button><button className="today-button" onClick={() => setDate(dayKey(new Date()))}>오늘</button></div>
    <div className="records-help"><NotebookPen size={16} /><p>“오늘 12시부터 13시까지 고객 미팅했고, 견적 수정 요청을 받았다고 기록해 줘.”</p></div>
    {selected.length === 0 && <div className="empty-state"><NotebookPen size={28} /><h3>이날 남긴 기록이 없어요</h3><p>위 마이크로 말하거나 기록 추가 버튼을 눌러 보세요.</p></div>}
    <div className="record-timeline">{selected.map(record => <article className="record-card" key={record.id}><div className="record-card-top"><span className="record-time">{clock(record.data.start)} – {clock(record.data.end)}{dayKey(new Date(record.data.start)) !== dayKey(new Date(record.data.end)) && <small> · 날짜를 걸친 기록</small>}</span><button className="icon-button" aria-label={`${record.data.title} 기록 수정`} disabled={busy} onClick={() => setEditing(record)}><Pencil size={16} /></button></div><h3>{record.data.title}</h3><p className="record-content">{record.data.description}</p>{record.eventTitle && <div className="record-link"><Link2 size={13} /><span>{record.eventId ? '연결된 일정' : '원래 연결된 일정'} · {record.eventTitle}</span></div>}<span className="record-storage">내부 기록</span></article>)}</div>
    {editing && <RecordEditor record={editing === 'new' ? null : editing} date={date} events={events} busy={busy} error={error} close={() => setEditing(null)} save={async (input, remove) => { if (await save(editing === 'new' ? null : editing, input, remove)) setEditing(null) }} />}
  </section>
}

function RecordEditor({ record, date, events, busy, error, close, save }: { record: ActivityRecord | null; date: string; events: CalendarEvent[]; busy: boolean; error: string; close: () => void; save: (data: RecordInput, remove: boolean) => Promise<void> }) {
  const dialog = useRef<HTMLDialogElement>(null)
  const [title, setTitle] = useState(record?.data.title || '')
  const [content, setContent] = useState(record?.data.description || '')
  const [start, setStart] = useState(record ? localValue(record.data.start) : date + 'T12:00')
  const [end, setEnd] = useState(record ? localValue(record.data.end) : date + 'T13:00')
  const [eventId, setEventId] = useState(record?.eventId || '')
  useEffect(() => { dialog.current?.showModal(); return () => dialog.current?.close() }, [])
  function input() { return { title, content, start: start + ':00+09:00', end: end + ':00+09:00', eventId } }
  const available = events.filter(e => !e.deleted)
  return <dialog className="modal" ref={dialog} onCancel={e => { e.preventDefault(); if (!busy) close() }}><div className="modal-heading"><h2>{record ? '활동 기록 수정' : '시간별 기록 추가'}</h2><button className="icon-button" aria-label="기록 창 닫기" disabled={busy} onClick={close}><X size={20} /></button></div><form className="event-form" onSubmit={e => { e.preventDefault(); void save(input(), false) }}>
    {error && <p className="record-form-error" role="alert">{error}</p>}
    <label className="field">기록 제목<input autoFocus required maxLength={200} value={title} onChange={e => setTitle(e.target.value)} placeholder="예: 고객 미팅" /></label>
    <div className="form-dates"><label className="field">기록 시작<input required type="datetime-local" value={start} onChange={e => setStart(e.target.value)} /></label><label className="field">기록 종료<input required type="datetime-local" value={end} onChange={e => setEnd(e.target.value)} /></label></div>
    <label className="field">기록 내용<textarea aria-label="기록 내용" required rows={5} maxLength={5000} value={content} onChange={e => setContent(e.target.value)} placeholder="어떤 일을 했고, 무엇을 기억해 두면 좋을까요?" /></label>
    <label className="field">일정 연결 (선택)<select value={eventId} onChange={e => setEventId(e.target.value)}><option value="">일정에 연결하지 않음</option>{record?.eventId && !available.some(e => e.id === record.eventId) && <option value={record.eventId}>{record.eventTitle || '기존 연결 일정'}</option>}{available.map(e => <option key={e.id} value={e.id}>{e.data.start.slice(0, 10)} · {e.data.title}</option>)}</select></label>
    <p className="helper">서울 시간 기준 · 이 기록은 서비스 내부에 저장됩니다. 일정 자체의 시간과 내용은 변경하지 않아요.</p>
    <div className="editor-actions">{record && <button type="button" className="danger-button" disabled={busy} onClick={() => save(input(), true)}><Trash2 size={16} />기록 삭제</button>}<button className="primary-button" type="submit" disabled={busy}>{record ? '기록 변경 확인' : '기록 저장'}</button></div>
  </form></dialog>
}
