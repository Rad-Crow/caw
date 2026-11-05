import { mount } from 'svelte'
import App from './App.svelte'
import './app.css'
import './lib/theme'

mount(App, { target: document.getElementById('app')! })
