import { useEffect, useRef, useState } from 'react'

export function useRecorder(onAudio: (blob: Blob) => void, onError: (message: string) => void) {
  const [recording, setRecording] = useState(false)
  const [seconds, setSeconds] = useState(0)
  const recorder = useRef<MediaRecorder | null>(null)
  const stream = useRef<MediaStream | null>(null)
  const timer = useRef<ReturnType<typeof setInterval> | null>(null)
  const mounted = useRef(true)
  const starting = useRef(false)
  const captureGeneration = useRef(0)
  const callbacks = useRef({ onAudio, onError }); callbacks.current = { onAudio, onError }
  const clean = () => {
    if (timer.current) clearInterval(timer.current)
    timer.current = null
    stream.current?.getTracks().forEach(t => t.stop()); stream.current = null
    if (mounted.current) setRecording(false)
  }
  const stop = () => { if (recorder.current?.state === 'recording') recorder.current.stop() }
  const cancel = () => {
    ++captureGeneration.current
    if (recorder.current) { recorder.current.onstop = null; recorder.current.ondataavailable = null; recorder.current.onerror = null; stop() }
    recorder.current = null; clean()
  }
  useEffect(() => {
    mounted.current = true
    const hidden = () => { if (document.hidden) stop() }
    document.addEventListener('visibilitychange', hidden)
    return () => { mounted.current = false; document.removeEventListener('visibilitychange', hidden); cancel() }
  }, [])
  async function toggle() {
    if (recording) { stop(); return }
    if (starting.current) return
    if (!window.isSecureContext || !navigator.mediaDevices?.getUserMedia) {
      callbacks.current.onError('마이크는 서버 PC의 localhost 또는 HTTPS에서 사용할 수 있어요. PC와 휴대폰 모두 HTTP IP 주소로 접속하면 마이크가 제한돼요.'); return
    }
    if (!window.MediaRecorder) { callbacks.current.onError('이 브라우저는 녹음을 지원하지 않아요. Safari 또는 Chrome 최신 버전에서 열어 주세요.'); return }
    const generation = ++captureGeneration.current
    try {
      starting.current = true
      window.speechSynthesis?.cancel()
      stream.current = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true }, video: false })
      if (!mounted.current || generation !== captureGeneration.current) { clean(); return }
      const mime = ['audio/webm;codecs=opus', 'audio/mp4', 'audio/webm'].find(m => MediaRecorder.isTypeSupported(m))
      const r = new MediaRecorder(stream.current, mime ? { mimeType: mime } : undefined)
      recorder.current = r
      const chunks: Blob[] = []
      let failed = false
      r.ondataavailable = e => { if (e.data.size) chunks.push(e.data) }
      r.onerror = () => { failed = true; clean(); callbacks.current.onError('녹음이 중단되었어요. 다시 시도해 주세요.') }
      r.onstop = () => {
        clean()
        if (mounted.current && !failed && generation === captureGeneration.current) callbacks.current.onAudio(new Blob(chunks, { type: r.mimeType }))
      }
      r.start(250); setRecording(true); setSeconds(0)
      const started = Date.now()
      timer.current = setInterval(() => {
        const elapsed = Math.floor((Date.now() - started) / 1000); setSeconds(elapsed)
        if (elapsed >= 60) stop()
      }, 250)
    } catch { clean(); if (mounted.current && generation === captureGeneration.current) callbacks.current.onError('마이크 권한을 허용해 주세요. 다른 앱이 마이크를 사용 중인지도 확인해 주세요.') }
    finally { starting.current = false }
  }
  return { recording, seconds, toggle, cancel }
}
