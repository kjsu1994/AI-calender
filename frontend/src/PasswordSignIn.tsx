import { useRef, useState, type FormEvent } from 'react'
import { ArrowUpRight, Eye, EyeOff, LoaderCircle, LockKeyhole, LogIn } from 'lucide-react'
import { api } from './api'

export default function PasswordSignIn({ googleConfigured, connected }: { googleConfigured: boolean; connected: () => Promise<void> }) {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [visible, setVisible] = useState(false)
  const [rememberMe, setRememberMe] = useState(true)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const sending = useRef(false)
  async function submit(e: FormEvent) {
    e.preventDefault()
    if (sending.current || !username.trim() || !password) return
    sending.current = true; setBusy(true); setError('')
    try {
      await api('/auth/password', 'POST', { username: username.trim(), password, rememberMe })
      setPassword(''); setUsername(''); setVisible(false)
      await connected()
    } catch (e) {
      setPassword(''); setVisible(false)
      setError(e instanceof Error ? e.message : '로그인하지 못했습니다. 잠시 후 다시 시도해 주세요.')
    } finally { sending.current = false; setBusy(false) }
  }
  return <section className="password-sign-in" aria-labelledby="password-sign-in-title">
    <div className="password-intro"><span className="password-symbol"><LockKeyhole size={23} /></span><span className="eyebrow">YOUR DAY, WHEREVER YOU ARE</span><h2 id="password-sign-in-title">나의 일정과 기록을<br /> 이어서.</h2><p>등록한 아이디로 로그인하면<br />PC와 휴대폰에서 같은 하루를 관리할 수 있어요.</p></div>
    <form className="password-form" onSubmit={submit} autoComplete="on">
      <label className="field">아이디<input aria-label="아이디" name="username" value={username} onChange={e => setUsername(e.target.value)} autoComplete="username" autoCapitalize="none" spellCheck={false} maxLength={128} placeholder="아이디를 입력하세요" disabled={busy} required /></label>
      <label className="field">비밀번호<span className="password-input-wrap"><input aria-label="비밀번호" name="password" type={visible ? 'text' : 'password'} value={password} onChange={e => setPassword(e.target.value)} autoComplete="current-password" placeholder="비밀번호를 입력하세요" disabled={busy} required /><button className="password-visibility" type="button" aria-label={visible ? '비밀번호 숨기기' : '비밀번호 보기'} aria-pressed={visible} disabled={busy} onClick={() => setVisible(!visible)}>{visible ? <EyeOff size={18} /> : <Eye size={18} />}</button></span></label>
      <div className="remember-setting"><label className="checkbox-field"><input type="checkbox" name="rememberMe" checked={rememberMe} onChange={e => setRememberMe(e.target.checked)} disabled={busy} />자동 로그인 (30일)</label><p className="helper">공용 기기에서는 선택을 해제해 주세요.</p></div>
      {error && <p className="record-form-error" role="alert">{error}</p>}
      <button className="primary-button password-submit" type="submit" disabled={busy || !username.trim() || !password}>{busy ? <LoaderCircle size={17} className="spin" /> : <LogIn size={17} />}{busy ? '로그인 중' : '로그인'}</button>
      {googleConfigured && <><div className="sign-in-divider"><span>또는</span></div><a className="secondary-button" href="/api/auth/google">Google 계정으로 시작 <ArrowUpRight size={15} /></a></>}
    </form>
  </section>
}
