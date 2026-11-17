#!/usr/bin/env node
/**
 * Minimal MCP (stdio) adapter for the memoX local Agent API.
 *
 * Transport: JSON-RPC 2.0 over stdin/stdout, one message per line.
 * The phone must be reachable at MEMOX_API_URL (default http://127.0.0.1:47823) through
 * `adb forward tcp:47823 tcp:47823`, and MEMOX_TOKEN must match the paired token shown in
 * memoX settings. No network listener is opened by this process.
 */

import { createInterface } from 'node:readline'

const API_URL = (process.env.MEMOX_API_URL || 'http://127.0.0.1:47823').replace(/\/$/, '')
const TOKEN = process.env.MEMOX_TOKEN || ''

const tools = [
  {
    name: 'memox_list_notes',
    description: 'List note metadata (titles, labels, timestamps). Encrypted bodies are never returned here.',
    inputSchema: {
      type: 'object',
      properties: {
        query: { type: 'string', description: 'Title/plain-body keyword' },
        label: { type: 'string' },
        from: { type: 'number', description: 'Created at or after (epoch ms)' },
        to: { type: 'number', description: 'Created at or before (epoch ms)' },
        limit: { type: 'number', default: 50 },
        offset: { type: 'number', default: 0 },
        trash: { type: 'boolean', default: false },
      },
    },
  },
  {
    name: 'memox_get_note',
    description: 'Read one note. For a content-encrypted note pass its recovery password; without it only metadata is returned.',
    inputSchema: {
      type: 'object',
      properties: {
        id: { type: 'string' },
        recovery_password: { type: 'string' },
      },
      required: ['id'],
    },
  },
  {
    name: 'memox_create_note',
    description: 'Create a plain note.',
    inputSchema: {
      type: 'object',
      properties: {
        title: { type: 'string' },
        body: { type: 'string' },
        format: { type: 'string', enum: ['RICH_TEXT', 'MARKDOWN'], default: 'RICH_TEXT' },
        labels: { type: 'array', items: { type: 'string' } },
      },
    },
  },
  {
    name: 'memox_update_note',
    description: 'Update a plain note. expected_version prevents overwriting a newer edit (409 conflict).',
    inputSchema: {
      type: 'object',
      properties: {
        id: { type: 'string' },
        title: { type: 'string' },
        body: { type: 'string' },
        labels: { type: 'array', items: { type: 'string' } },
        expected_version: { type: 'number' },
      },
      required: ['id'],
    },
  },
  {
    name: 'memox_trash_note',
    description: 'Move a note to the recycle bin. Permanent deletion of protected notes requires on-device confirmation and is refused here.',
    inputSchema: {
      type: 'object',
      properties: {
        id: { type: 'string' },
        permanent: { type: 'boolean', default: false },
      },
      required: ['id'],
    },
  },
  {
    name: 'memox_sync',
    description: 'Trigger the configured WebDAV/OneDrive sync.',
    inputSchema: { type: 'object', properties: {} },
  },
  {
    name: 'memox_status',
    description: 'Report API/sync status and note count.',
    inputSchema: { type: 'object', properties: {} },
  },
]

function send(message) {
  process.stdout.write(JSON.stringify(message) + '\n')
}

async function api(path, { method = 'GET', body, recovery } = {}) {
  if (!TOKEN) throw new Error('MEMOX_TOKEN is not configured')
  const headers = { Authorization: `Bearer ${TOKEN}` }
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (recovery) headers['X-Memox-Recovery'] = recovery
  const response = await fetch(API_URL + path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await response.text()
  let payload
  try {
    payload = text ? JSON.parse(text) : {}
  } catch {
    payload = { raw: text }
  }
  if (!response.ok) {
    const error = new Error(payload.error || `HTTP ${response.status}`)
    error.status = response.status
    error.payload = payload
    throw error
  }
  return payload
}

async function callTool(name, args) {
  switch (name) {
    case 'memox_list_notes': {
      const query = new URLSearchParams()
      for (const key of ['query', 'label', 'from', 'to', 'limit', 'offset', 'trash']) {
        if (args[key] !== undefined && args[key] !== null) query.set(key, String(args[key]))
      }
      return api(`/v1/notes?${query.toString()}`)
    }
    case 'memox_get_note':
      return api(`/v1/notes/${encodeURIComponent(args.id)}`, {
        recovery: args.recovery_password,
      })
    case 'memox_create_note':
      return api('/v1/notes', {
        method: 'POST',
        body: {
          title: args.title || '',
          body: args.body || '',
          format: args.format || 'RICH_TEXT',
          labels: args.labels || [],
        },
      })
    case 'memox_update_note': {
      const body = {}
      for (const key of ['title', 'body', 'labels']) {
        if (args[key] !== undefined) body[key] = args[key]
      }
      if (args.expected_version !== undefined) body.expectedVersion = args.expected_version
      return api(`/v1/notes/${encodeURIComponent(args.id)}`, { method: 'PATCH', body })
    }
    case 'memox_trash_note':
      return api(
        `/v1/notes/${encodeURIComponent(args.id)}?permanent=${args.permanent ? 'true' : 'false'}`,
        { method: 'DELETE' },
      )
    case 'memox_sync':
      return api('/v1/sync', { method: 'POST' })
    case 'memox_status':
      return api('/v1/status')
    default:
      throw new Error(`Unknown tool: ${name}`)
  }
}

async function handle(message) {
  const { id, method, params } = message
  const isNotification = id === undefined
  try {
    if (method === 'initialize') {
      send({
        jsonrpc: '2.0',
        id,
        result: {
          protocolVersion: params?.protocolVersion || '2024-11-05',
          capabilities: { tools: {} },
          serverInfo: { name: 'memox-local', version: '1.0.0' },
        },
      })
    } else if (method === 'notifications/initialized') {
      return
    } else if (method === 'tools/list') {
      send({ jsonrpc: '2.0', id, result: { tools } })
    } else if (method === 'tools/call') {
      const result = await callTool(params?.name, params?.arguments || {})
      send({
        jsonrpc: '2.0',
        id,
        result: { content: [{ type: 'text', text: JSON.stringify(result, null, 2) }] },
      })
    } else if (method === 'ping') {
      send({ jsonrpc: '2.0', id, result: {} })
    } else if (!isNotification) {
      send({ jsonrpc: '2.0', id, error: { code: -32601, message: `Unknown method ${method}` } })
    }
  } catch (error) {
    if (isNotification) return
    send({
      jsonrpc: '2.0',
      id,
      error: {
        code: error.status === 409 ? -32009 : -32603,
        message: error.message || String(error),
        data: error.payload,
      },
    })
  }
}

const reader = createInterface({ input: process.stdin })
let queue = Promise.resolve()
reader.on('line', (line) => {
  const trimmed = line.trim()
  if (!trimmed) return
  let message
  try {
    message = JSON.parse(trimmed)
  } catch {
    send({ jsonrpc: '2.0', id: null, error: { code: -32700, message: 'Parse error' } })
    return
  }
  // Serialize calls so two mutations cannot interleave in the adapter.
  queue = queue.then(() => handle(message))
})
