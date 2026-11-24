(function () {
  if (window.__nwShimInitialized) return;
  window.__nwShimInitialized = true;

  const pendingCalls = new Map();
  let callCounter = 1;

  function emitDiagnostic(level, message) {
    try {
      if (window.__nwNativeBridge && window.__nwNativeBridge.postMessage) {
        window.__nwNativeBridge.postMessage(JSON.stringify({
          type: 'console',
          level: level,
          message: String(message)
        }));
      }
    } catch (_) {}
  }

  ['log', 'info', 'warn', 'error'].forEach(function (lvl) {
    const orig = console[lvl] ? console[lvl].bind(console) : function () {};
    console[lvl] = function () {
      const msg = Array.prototype.slice.call(arguments).map(function (a) {
        if (a instanceof Error) return a.stack || a.message;
        if (typeof a === 'object') { try { return JSON.stringify(a); } catch (_) { return String(a); } }
        return String(a);
      }).join(' ');
      emitDiagnostic(lvl, msg);
      orig.apply(console, arguments);
    };
  });

  window.addEventListener('error', function (ev) {
    emitDiagnostic('error', '[Uncaught Error] ' + (ev.message || ev.error));
  });
  window.addEventListener('unhandledrejection', function (ev) {
    const r = ev.reason;
    emitDiagnostic('error', '[Unhandled Promise] ' + (r && r.stack ? r.stack : String(r)));
  });

  window.__nwResolveRpc = function (id, ok, payloadJson) {
    const entry = pendingCalls.get(id);
    if (!entry) return;
    pendingCalls.delete(id);
    let parsed = null;
    if (payloadJson !== undefined && payloadJson !== null && payloadJson !== '') {
      try { parsed = JSON.parse(payloadJson); } catch (_) { parsed = payloadJson; }
    }
    if (ok) entry.resolve(parsed);
    else entry.reject(new Error(typeof parsed === 'string' ? parsed : (parsed && parsed.error) || 'Bridge error'));
  };

  function rpc(method, params) {
    return new Promise(function (resolve, reject) {
      const id = callCounter++;
      pendingCalls.set(id, { resolve: resolve, reject: reject });
      try {
        window.__nwNativeBridge.postMessage(JSON.stringify({
          type: 'rpc',
          id: id,
          method: method,
          params: params || {}
        }));
      } catch (e) {
        pendingCalls.delete(id);
        reject(e);
      }
    });
  }

  function makeDataArray(items) {
    const arr = Array.isArray(items) ? items.slice() : [];
    const api = {
      values: arr,
      get length() { return arr.length; },
      array: function () { return arr.slice(); },
      filter: function (pred) { return makeDataArray(arr.filter(pred)); },
      where: function (pred) { return makeDataArray(arr.filter(pred)); },
      map: function (fn) { return makeDataArray(arr.map(fn)); },
      flatMap: function (fn) {
        const out = [];
        arr.forEach(function (item, idx) {
          const res = fn(item, idx, arr);
          if (Array.isArray(res)) out.push.apply(out, res);
          else if (res && Array.isArray(res.values)) out.push.apply(out, res.values);
          else if (res !== undefined) out.push(res);
        });
        return makeDataArray(out);
      },
      sort: function (keyFn, dir) {
        const copy = arr.slice();
        const factor = (String(dir || 'asc').toLowerCase() === 'desc') ? -1 : 1;
        copy.sort(function (a, b) {
          const ka = typeof keyFn === 'function' ? keyFn(a) : (a && a[keyFn]);
          const kb = typeof keyFn === 'function' ? keyFn(b) : (b && b[keyFn]);
          if (ka === kb) return 0;
          if (ka === undefined || ka === null) return 1;
          if (kb === undefined || kb === null) return -1;
          if (typeof ka === 'number' && typeof kb === 'number') return (ka - kb) * factor;
          return String(ka).localeCompare(String(kb)) * factor;
        });
        return makeDataArray(copy);
      },
      groupBy: function (keyFn) {
        const groups = new Map();
        arr.forEach(function (item) {
          const k = typeof keyFn === 'function' ? keyFn(item) : (item && item[keyFn]);
          if (!groups.has(k)) groups.set(k, []);
          groups.get(k).push(item);
        });
        const out = [];
        groups.forEach(function (rows, k) {
          out.push({ key: k, rows: makeDataArray(rows) });
        });
        return makeDataArray(out);
      },
      distinct: function (keyFn) {
        const seen = new Set();
        const out = [];
        arr.forEach(function (item) {
          const k = keyFn ? keyFn(item) : item;
          if (!seen.has(k)) { seen.add(k); out.push(item); }
        });
        return makeDataArray(out);
      },
      slice: function (s, e) { return makeDataArray(arr.slice(s, e)); },
      limit: function (n) { return makeDataArray(arr.slice(0, n)); },
      find: function (pred) { return arr.find(pred); },
      first: function () { return arr[0]; },
      last: function () { return arr[arr.length - 1]; },
      forEach: function (fn) { arr.forEach(fn); },
      some: function (pred) { return arr.some(pred); },
      every: function (pred) { return arr.every(pred); },
      reduce: function (fn, init) { return arguments.length > 1 ? arr.reduce(fn, init) : arr.reduce(fn); },
      includes: function (v) { return arr.includes(v); },
      join: function (sep) { return arr.join(sep); }
    };
    api[Symbol.iterator] = function () { return arr[Symbol.iterator](); };
    return new Proxy(api, {
      get: function (target, prop) {
        if (typeof prop === 'string' && /^\d+$/.test(prop)) return arr[Number(prop)];
        return Reflect.get(target, prop);
      }
    });
  }

  // Synchronous local cache hydrated before page script starts
  const pageState = {
    pagesByPath: new Map(),
    allPages: [],
    filesByPath: new Map(),
    localKv: {}
  };

  window.__nwHydrateContext = function (ctxJson) {
    const ctx = typeof ctxJson === 'string' ? JSON.parse(ctxJson) : ctxJson;
    pageState.allPages = ctx.pages || [];
    pageState.pagesByPath.clear();
    pageState.filesByPath.clear();
    pageState.allPages.forEach(function (p) {
      if (p && p.file && p.file.path) {
        pageState.pagesByPath.set(p.file.path, p);
        pageState.pagesByPath.set(p.file.path.replace(/\.md$/i, ''), p);
        pageState.pagesByPath.set(p.file.name, p);
      }
    });
    (ctx.abstractFiles || []).forEach(function (f) {
      pageState.filesByPath.set(f.path, f);
    });
    pageState.localKv = ctx.localStorage || {};
  };

  function matchSingleQueryTerm(page, term) {
    term = term.trim();
    if (!term) return true;
    const neg = term.startsWith('-');
    if (neg) term = term.slice(1).trim();
    let matched = true;
    if (term.startsWith('#')) {
      const tag = term.slice(1).toLowerCase();
      const tags = (page.file && page.file.tags ? page.file.tags : []).map(function (t) { return String(t).replace(/^#/, '').toLowerCase(); });
      matched = tags.some(function (t) { return t === tag || t.startsWith(tag + '/'); });
    } else if ((term.startsWith('"') && term.endsWith('"')) || (term.startsWith("'") && term.endsWith("'"))) {
      const folder = term.slice(1, -1).replace(/^\/+|\/+$/g, '').toLowerCase();
      const fp = (page.file && page.file.path ? page.file.path : '').toLowerCase();
      matched = fp === folder || fp.startsWith(folder + '/') || fp.indexOf('/' + folder + '/') >= 0;
    } else {
      const fp = (page.file && page.file.path ? page.file.path : '').toLowerCase();
      matched = fp.indexOf(term.toLowerCase()) >= 0;
    }
    return neg ? !matched : matched;
  }

  function queryPages(query) {
    if (!query || !String(query).trim()) return makeDataArray(pageState.allPages);
    const orParts = String(query).split(/\s+or\s+/i);
    const filtered = pageState.allPages.filter(function (page) {
      return orParts.some(function (orClause) {
        const andParts = orClause.split(/\s+and\s+/i);
        return andParts.every(function (t) { return matchSingleQueryTerm(page, t); });
      });
    });
    return makeDataArray(filtered);
  }

  function makeAbstractFile(meta) {
    if (!meta) return null;
    if (meta.isFolder) {
      return {
        path: meta.path,
        name: meta.name,
        parent: meta.parent ? { path: meta.parent } : null,
        get children() {
          const prefix = meta.path ? meta.path + '/' : '';
          const out = [];
          pageState.filesByPath.forEach(function (f) {
            if (f.path.startsWith(prefix)) {
              const rest = f.path.slice(prefix.length);
              if (rest && rest.indexOf('/') === -1) out.push(makeAbstractFile(f));
            }
          });
          return out;
        }
      };
    }
    return {
      path: meta.path,
      name: meta.name,
      basename: meta.name.replace(/\.[^.]+$/, ''),
      extension: meta.name.indexOf('.') >= 0 ? meta.name.split('.').pop() : '',
      stat: { mtime: meta.mtime || 0, size: meta.size || 0 },
      parent: meta.parent ? { path: meta.parent } : null
    };
  }

  const rootContainer = document.getElementById('nw-page-root') || document.body;

  window.Notice = function (message, timeout) {
    rpc('notice', { message: String(message), timeout: timeout || 4000 }).catch(function () {});
  };

  window.requestUrl = async function (req) {
    const opts = typeof req === 'string' ? { url: req, method: 'GET' } : req;
    const res = await rpc('requestUrl', {
      url: opts.url,
      method: opts.method || 'GET',
      headers: opts.headers || {},
      body: opts.body || null
    });
    return {
      status: res.status || 200,
      headers: res.headers || {},
      text: res.text || '',
      json: (function () { try { return JSON.parse(res.text || 'null'); } catch (_) { return null; } })(),
      arrayBuffer: new ArrayBuffer(0)
    };
  };

  if (!navigator.clipboard) navigator.clipboard = {};
  navigator.clipboard.writeText = function (text) {
    return rpc('clipboardWrite', { text: String(text) });
  };

  window.moment = function (inp) {
    const d = inp ? new Date(inp) : new Date();
    return {
      format: function (fmt) {
        const y = d.getFullYear();
        const m = String(d.getMonth() + 1).padStart(2, '0');
        const day = String(d.getDate()).padStart(2, '0');
        const hh = String(d.getHours()).padStart(2, '0');
        const mm = String(d.getMinutes()).padStart(2, '0');
        if (!fmt) return d.toISOString();
        return String(fmt).replace('YYYY', y).replace('MM', m).replace('DD', day).replace('HH', hh).replace('mm', mm);
      },
      valueOf: function () { return d.getTime(); },
      toDate: function () { return d; }
    };
  };

  // Isolated per-page localStorage proxy
  const pageLocalStorage = {
    getItem: function (k) { return Object.prototype.hasOwnProperty.call(pageState.localKv, k) ? pageState.localKv[k] : null; },
    setItem: function (k, v) {
      pageState.localKv[k] = String(v);
      rpc('storageSet', { key: String(k), value: String(v) }).catch(function () {});
    },
    removeItem: function (k) {
      delete pageState.localKv[k];
      rpc('storageRemove', { key: String(k) }).catch(function () {});
    },
    clear: function () {
      pageState.localKv = {};
      rpc('storageClear', {}).catch(function () {});
    }
  };
  try {
    Object.defineProperty(window, 'localStorage', { value: pageLocalStorage, configurable: true });
  } catch (_) {}

  window.app = {
    vault: {
      getAbstractFileByPath: function (path) {
        const norm = String(path || '').replace(/^\/+/, '');
        const direct = pageState.filesByPath.get(norm);
        if (direct) return makeAbstractFile(direct);
        // Check alias match or .md fallback
        const md = pageState.filesByPath.get(norm + '.md');
        if (md) return makeAbstractFile(md);
        return null;
      },
      cachedRead: async function (fileOrPath) {
        const p = typeof fileOrPath === 'string' ? fileOrPath : (fileOrPath && fileOrPath.path);
        const res = await rpc('vaultRead', { path: p });
        return res.text;
      },
      read: async function (fileOrPath) {
        const p = typeof fileOrPath === 'string' ? fileOrPath : (fileOrPath && fileOrPath.path);
        const res = await rpc('vaultRead', { path: p });
        return res.text;
      },
      modify: async function (fileOrPath, content) {
        const p = typeof fileOrPath === 'string' ? fileOrPath : (fileOrPath && fileOrPath.path);
        await rpc('vaultWrite', { path: p, content: String(content), createOnly: false });
      },
      create: async function (path, content) {
        const res = await rpc('vaultWrite', { path: String(path), content: String(content), createOnly: true });
        const meta = { path: res.path, name: res.path.split('/').pop(), isFolder: false, mtime: Date.now() };
        pageState.filesByPath.set(meta.path, meta);
        return makeAbstractFile(meta);
      },
      createFolder: async function (path) {
        await rpc('vaultCreateFolder', { path: String(path) });
        const meta = { path: String(path), name: String(path).split('/').pop(), isFolder: true };
        pageState.filesByPath.set(meta.path, meta);
      },
      createBinary: async function (path, arrayBuffer) {
        const bytes = new Uint8Array(arrayBuffer);
        let bin = '';
        for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
        await rpc('vaultCreateBinary', { path: String(path), base64: btoa(bin) });
      },
      delete: async function (fileOrPath) {
        const p = typeof fileOrPath === 'string' ? fileOrPath : (fileOrPath && fileOrPath.path);
        await rpc('vaultDelete', { path: p });
        pageState.filesByPath.delete(p);
      },
      getResourcePath: function (fileOrPath) {
        const p = typeof fileOrPath === 'string' ? fileOrPath : (fileOrPath && fileOrPath.path);
        return 'https://appassets.androidplatform.net/vault/' + encodeURI(String(p || '').replace(/^\/+/, ''));
      },
      adapter: {
        exists: async function (path) {
          const res = await rpc('vaultExists', { path: String(path) });
          return !!res.exists;
        },
        read: async function (path) {
          const res = await rpc('vaultRead', { path: String(path) });
          return res.text;
        },
        write: async function (path, content) {
          await rpc('vaultWrite', { path: String(path), content: String(content), createOnly: false });
        },
        remove: async function (path) {
          await rpc('vaultDelete', { path: String(path) });
        }
      }
    },
    fileManager: {
      processFrontMatter: async function (fileOrPath, fn) {
        const p = typeof fileOrPath === 'string' ? fileOrPath : (fileOrPath && fileOrPath.path);
        const res = await rpc('readFrontmatter', { path: p });
        const fm = res.frontmatter || {};
        fn(fm);
        await rpc('writeFrontmatter', { path: p, frontmatter: fm });
        // Update local page cache immediately
        const cached = pageState.pagesByPath.get(p);
        if (cached) Object.assign(cached, fm);
      }
    },
    workspace: {
      getLeaf: function () {
        return {
          openFile: async function (fileOrPath) {
            const p = typeof fileOrPath === 'string' ? fileOrPath : (fileOrPath && fileOrPath.path);
            await rpc('openFile', { path: p });
          }
        };
      }
    }
  };

  window.dv = {
    container: rootContainer,
    el: function (tag, text, opts) {
      const el = document.createElement(tag);
      if (text !== undefined && text !== null) el.textContent = String(text);
      if (opts && opts.cls) el.className = opts.cls;
      if (opts && opts.attr) Object.keys(opts.attr).forEach(function (k) { el.setAttribute(k, opts.attr[k]); });
      rootContainer.appendChild(el);
      return el;
    },
    header: function (level, text) { return window.dv.el('h' + level, text); },
    paragraph: function (text) { return window.dv.el('p', text); },
    span: function (text) { return window.dv.el('span', text); },
    page: function (path) {
      const norm = String(path || '').replace(/^\/+/, '');
      return pageState.pagesByPath.get(norm)
        || pageState.pagesByPath.get(norm.replace(/\.md$/i, ''))
        || null;
    },
    pages: function (query) { return queryPages(query); },
    view: async function (scriptPath, input) {
      const mod = await rpc('resolveModule', { path: String(scriptPath) });
      if (!mod || !mod.code) {
        throw new Error('dv.view module could not be resolved: ' + scriptPath);
      }
      const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;
      const runner = new AsyncFunction('input', 'dv', 'app', 'Notice', 'requestUrl', 'moment', 'localStorage', mod.code);
      return await runner(input || {}, window.dv, window.app, window.Notice, window.requestUrl, window.moment, pageLocalStorage);
    }
  };
})();
