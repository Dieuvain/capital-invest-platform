import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { Api } from './api'
import { App } from './App'
import './styles.css'

const api = new Api(import.meta.env.VITE_API_URL ?? '')

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App api={api} />
  </StrictMode>,
)
