import { useEffect, useRef, useState, type FormEvent } from 'react'
import { ArrowUpRight, Clock3, Link2, LoaderCircle, Smartphone } from 'lucide-react'
import { api } from './api'

export function isCanonicalOrigin(publicBaseUrl: string) {
  try { return new URL(publicBaseUrl).origin === window.location.origin }
  catch { return false }
}

export function getPublicHttpsOrigin(publicBaseUrl: string) {
  try {
    const url = new URL(publicBaseUrl)
    const hostname = url.hostname.toLowerCase().replace(/\.$/, '')
    const dnsName = /^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/i
    if (url.protocol !== 'https:' || url.username || url.password || !dnsName.test(hostname)
      || hostname.endsWith('.localhost') || /^(?:\d+\.){3}\d+$/.test(hostname)) return null
    return url.origin
  } catch { return null }
}

export function DeviceSignIn({ publicBaseUrl, connected }: { publicBaseUrl: string; connected: () => Promise<void> }) {
  const [code, setCode] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const sending = useRef(false)
  const normalized = code.replace(/[^A-Z0-9]/gi, '')
  const publicHttpsOrigin = getPublicHttpsOrigin(publicBaseUrl)
  async function submit(e: FormEvent) {
    e.preventDefault()
    if (sending.current || normalized.length !== 12) return
    sending.current = true; setBusy(true); setError('')
    try { await api('/auth/device-link', 'POST', { code }); setCode(''); await connected() }
    catch (e) { setError(e instanceof Error ? e.message : '기기를 연결하지 못했습니다. 다시 시도해 주세요.') }
    finally { sending.current = false; setBusy(false) }
  }
  if (publicHttpsOrigin) return <section className="welcome-panel" aria-label="HTTPS 주소 안내">
    <div><span className="eyebrow">CONTINUE WITH YOUR VOICE</span><h2>HTTPS 주소에서 계속하세요</h2><p>이 주소로 접속하면 Google 로그인과 음성 입력을 사용할 수 있어요.</p><p className="server-address">{publicHttpsOrigin}</p></div>
    <a className="primary-button" href={publicHttpsOrigin}>HTTPS 주소로 접속 <ArrowUpRight size={18} /></a>
  </section>
  return <section className="device-sign-in" aria-label="기기 연결">
    <div className="device-instructions"><span className="eyebrow">CONTINUE ON THIS DEVICE</span><h2><Smartphone size={22} />PC에서 연결 코드 발급</h2><p>서버 PC에서 <strong className="server-address">{publicBaseUrl}</strong>에 로그인한 뒤, <strong>연결 및 설정 → 연결 코드 생성</strong>을 선택해 주세요.</p><p>발급한 코드를 여기에 입력하면 같은 일정과 기록을 사용할 수 있어요.</p></div>
    <form className="device-link-form" onSubmit={submit}>
      <label className="field">연결 코드<input className="device-code-input" value={code} onChange={e => setCode((e.target.value.replace(/[^a-z0-9]/gi, '').slice(0, 12).toUpperCase().match(/.{1,4}/g) || []).join('-'))} autoComplete="off" autoCapitalize="characters" spellCheck={false} placeholder="ABCD-EFGH-JKLM" maxLength={14} disabled={busy} aria-describedby="device-code-help" required /></label>
      <p className="helper" id="device-code-help">코드는 발급 후 5분 동안 한 번 사용할 수 있어요.</p>
      {error && <p className="record-form-error" role="alert">{error}</p>}
      <button className="primary-button" type="submit" disabled={busy || normalized.length !== 12}>{busy ? <LoaderCircle size={16} className="spin" /> : <Link2 size={16} />}이 기기 연결</button>
    </form>
  </section>
}

export function DeviceCode() {
  const [issued, setIssued] = useState<{ code: string; expiresAt: string } | null>(null)
  const [now, setNow] = useState(Date.now())
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const sending = useRef(false)
  useEffect(() => {
    if (!issued) return
    const timer = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(timer)
  }, [issued])
  const remaining = issued ? Math.max(0, Math.ceil((new Date(issued.expiresAt).getTime() - now) / 1000)) : 0
  async function issue() {
    if (sending.current) return
    sending.current = true; setBusy(true); setError(''); setIssued(null)
    try { const result = await api<{ code: string; expiresAt: string }>('/auth/device-code', 'POST'); setNow(Date.now()); setIssued(result) }
    catch (e) { setError(e instanceof Error ? e.message : '연결 코드를 만들지 못했습니다. 다시 시도해 주세요.') }
    finally { sending.current = false; setBusy(false) }
  }
  return <section className="device-code-settings" aria-label="다른 기기 연결">
    <h3><Smartphone size={17} />다른 기기에서 이어 쓰기</h3>
    <p className="helper">같은 네트워크의 휴대폰에서 서버 IP 주소로 접속하고, 아래 코드를 입력해 주세요. 코드를 아는 기기는 내 일정과 기록에 접근할 수 있어요.</p>
    {issued && <div className="issued-device-code">{remaining > 0 ? <><output aria-label="발급한 연결 코드">{issued.code}</output><span><Clock3 size={13} />남은 시간 {Math.floor(remaining / 60)}:{String(remaining % 60).padStart(2, '0')} · 한 번만 사용</span></> : <p>코드가 만료되었습니다. 새 코드를 만들어 주세요.</p>}</div>}
    {error && <p className="record-form-error" role="alert">{error}</p>}
    <button className="secondary-button" disabled={busy} onClick={issue}>{busy ? <LoaderCircle size={16} className="spin" /> : <ArrowUpRight size={16} />}{issued ? '새 연결 코드 생성' : '연결 코드 생성'}</button>
  </section>
}
