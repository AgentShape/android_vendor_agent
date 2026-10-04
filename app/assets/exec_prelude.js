// exec_js 的 JS 接口（ADR-0024）：sh、fs、screen。宿主只提供 __host(name, json) → json，其余都在这里。
// 宿主函数名、返回对象的字段与 src/moe/uni/agent/tools/js/JsHost.kt 一一对应；节点和选择器的字段见 AgentFramework 的 a11y/NodeFields.kt，
// 屏幕结果的字段见 AgentFramework 的 control/ResultFields.kt。改动时两边同步。
// 节点对象就是 query 返回的节点 JSON，含 offscreen（ADR-0022 第 9b 条，NodeFields.OFFSCREEN）；nodeSelector 重新定位时不用它。
// 错误是带 code 的 Error，code 与工具结果的 error.code 相同（ADR-0030）。
// 等待（ADR-0034 第 5 条）：事件的字段见 AgentFramework 的 control/ResultFields.kt（WaitEventFields），
// mark/watch/waitEvents 返回值的字段见 src/moe/uni/agent/tools/js/ScriptEvents.kt。
(function () {
  "use strict";

  function fail(code, message) {
    const e = new Error(message);
    e.code = code;
    return e;
  }

  function call(name, args) {
    const r = JSON.parse(__host(name, JSON.stringify(args || {})));
    if (!r.ok) throw fail(r.error.code, r.error.message);
    return r.value;
  }

  function isNode(t) {
    return t !== null && typeof t === "object" && typeof t.element === "number" && Array.isArray(t.bounds);
  }

  // 节点对象按位置、类名和 id、文字在当时的界面上重新定位；位置和内容都相同的节点点哪个都一样，取第一个。
  function nodeSelector(n) {
    const s = { bounds: n.bounds, cls: n.cls };
    for (const k of ["id", "text", "desc"]) if (n[k] !== undefined) s[k] = n[k];
    return s;
  }

  // ---- 等待（ADR-0034 第 5 条）----

  const WAIT_MS = 5000;
  /** 两次判断条件的最小间隔，以及没有事件时的兜底唤醒间隔。 */
  const EVAL_MS = 100;
  const TICK_MS = 1000;
  /** 与 AgentFramework 的 UI 稳定判断相同：这么久没有变化即为稳定。 */
  const QUIET_MS = 250;
  /** 不匹配任何节点的选择器：只取屏幕信息。 */
  const NO_NODES = { index: -1 };
  /** 各类事件按文字匹配时看的字段。 */
  const MAIN_FIELDS = {
    window: ["text"], pane: ["text"], toast: ["text"], announcement: ["text"], notification: ["title", "text"], file: ["path"],
  };

  /** 挂了 when 或 timeout 还没有 wait 的起点：脚本结束时报错。 */
  let chains = [];

  function textMatch(value, p) {
    if (typeof value !== "string") return false;
    return p instanceof RegExp ? p.test(value) : value.includes(String(p));
  }

  // f 是文字或正则时匹配这类事件的主要字段；是对象时每个字段都要满足（文字、正则或相等）。
  function eventMatch(e, f) {
    if (f === undefined || f === null) return true;
    if (typeof f === "string" || f instanceof RegExp) return (MAIN_FIELDS[e.type] || []).some((k) => textMatch(e[k], f));
    return Object.keys(f).every((k) => (typeof f[k] === "string" || f[k] instanceof RegExp ? textMatch(e[k], f[k]) : e[k] === f[k]));
  }

  // 起点：只看它之后的事件。start 是宿主 mark/watch 的返回值 {seq, t}。
  function Mark(screen, start, watchId) {
    this.screen = screen;
    this.seq = start.seq;
    this.t = start.t;
    this.watchId = watchId || 0;
    this.conds = [];
    this.onTimeout = null;
    this.waited = false;
  }

  Mark.prototype = {
    when(cond, handler) {
      if (typeof cond !== "function") {
        if (cond === null || typeof cond !== "object") throw fail("invalid_argument", "when() 的条件要是函数或选择器");
        const sel = cond;
        cond = (s) => s.find(sel);
      }
      this.pending();
      this.conds.push({ cond, handler });
      return this;
    },
    timeout(handler) {
      this.pending();
      this.onTimeout = handler;
      return this;
    },
    pending() {
      if (this.waited) throw fail("invalid_argument", "这个起点已经 wait 过");
      if (!chains.includes(this)) chains.push(this);
    },
    // 判断条件，命中第一个就执行它的 handler；超时执行 timeout 的 handler，没有则抛 timeout。
    wait(ms) {
      if (this.waited) throw fail("invalid_argument", "这个起点已经 wait 过");
      this.waited = true;
      chains = chains.filter((c) => c !== this);
      const timeout = ms || WAIT_MS;
      const s = new WaitState(this);
      const end = s.begin + timeout;
      let hit = -1;
      let value;
      for (;;) {
        s.evaluated = Date.now();
        for (let i = 0; i < this.conds.length && hit < 0; i++) {
          value = this.conds[i].cond(s);
          if (value) hit = i;
        }
        if (hit >= 0 || Date.now() >= end) break;
        s.pull(end);
      }
      if (this.watchId) call("unwatch", { id: this.watchId });
      call("waitDone", {
        display: this.screen.display, matched: hit >= 0 ? hit : "timeout", elapsed_ms: s.elapsed, settled: s.settled,
        events: s.events.length,
      });
      if (hit >= 0) {
        const h = this.conds[hit].handler;
        return h ? h(s, value) : value;
      }
      if (this.onTimeout) return this.onTimeout(s);
      let top = null;
      try {
        top = s.state().top;
      } catch (e) {
        // 屏幕不可用时只是少一条信息。
      }
      throw fail("timeout", "等待 " + timeout + "ms 没有满足任何条件；top=" + top + "，settled=" + s.settled +
        "，最近的事件：" + JSON.stringify(s.events.slice(-3)));
    },
  };

  // 条件和 handler 拿到的上下文 s：起点之后的事件、当前节点树（按版本和选择器缓存）、是否稳定。
  function WaitState(mark) {
    this.mark = mark;
    this.main = mark.screen.display === 0;
    this.begin = Date.now();
    this.evaluated = 0;
    this.seq = mark.seq;
    this.events = [];
    this.version = 0;
    this.idle = 0;
    this.cache = {};
    this.absorb(0);
  }

  WaitState.prototype = {
    get settled() {
      return this.idle >= QUIET_MS;
    },
    get elapsed() {
      return Date.now() - this.begin;
    },
    // 收下新事件：屏幕类事件只要起点所在的屏幕，通知和文件不分屏幕。
    absorb(timeout) {
      const r = call("waitEvents", { since: this.seq, from: this.mark.t, timeout });
      for (const e of r.events) if (e.main === undefined || e.main === this.main) this.events.push(e);
      this.seq = r.seq;
      const key = this.main ? "main" : "virtual";
      if (r.version[key] !== this.version) this.cache = {};
      this.version = r.version[key];
      this.idle = r.idle[key];
    },
    // 等到有新事件或兜底唤醒；两次判断至少间隔 EVAL_MS，期间到达的事件一并收下。
    pull(end) {
      this.absorb(Math.max(0, Math.min(end - Date.now(), TICK_MS)));
      const gap = Math.min(this.evaluated + EVAL_MS, end) - Date.now();
      if (gap > 0) {
        sleep(gap);
        this.absorb(0);
      }
    },
    query(sel) {
      const key = JSON.stringify(sel);
      if (!(key in this.cache)) {
        this.cache[key] = call("query", { display: this.mark.screen.display, selector: sel, settle: false });
      }
      return this.cache[key];
    },
    findAll(sel) {
      return this.query(sel || {}).nodes;
    },
    find(sel) {
      return this.findAll(sel)[0] || null;
    },
    state() {
      const r = Object.assign({}, this.query(NO_NODES));
      delete r.nodes;
      return r;
    },
    latest(type, f, extra) {
      for (let i = this.events.length - 1; i >= 0; i--) {
        const e = this.events[i];
        if (e.type === type && (!extra || extra(e)) && eventMatch(e, f)) return e;
      }
      return null;
    },
    window(f) {
      return this.latest("window", f);
    },
    pane(f) {
      return this.latest("pane", f);
    },
    toast(f) {
      return this.latest("toast", f);
    },
    announcement(f) {
      return this.latest("announcement", f);
    },
    notification(f) {
      return this.latest("notification", f);
    },
    file(f, change) {
      return this.latest("file", f, change ? (e) => e.change === change : null);
    },
  };

  // 动作的返回值同时是起点：when/timeout/wait 不可枚举，不影响 JSON。
  function startFrom(result, mark) {
    for (const k of ["when", "timeout", "wait"]) Object.defineProperty(result, k, { value: mark[k].bind(mark) });
    return result;
  }

  function Screen(display) {
    this.display = display;
  }

  Screen.prototype = {
    on(display) {
      return new Screen(display);
    },
    list() {
      return call("list", {});
    },
    state() {
      return call("query", { display: this.display, selector: {} });
    },
    findAll(selector) {
      return call("query", { display: this.display, selector: selector || {} }).nodes;
    },
    find(selector) {
      const nodes = this.findAll(selector);
      if (nodes.length > 1) {
        throw fail("invalid_argument", "选择器匹配到 " + nodes.length + " 个元素：" + JSON.stringify(selector));
      }
      return nodes[0] || null;
    },
    // 以现在为起点。
    mark() {
      return new Mark(this, call("mark", {}));
    },
    // 开始监听这些文件或目录（不递归），并以现在为起点；要先 watch 再触发。
    watch(...paths) {
      const r = call("watch", { paths: paths.map(String) });
      return new Mark(this, r, r.id);
    },
    screenshot() {
      return call("screenshot", { display: this.display });
    },

    // 执行动作前在当时的界面上唯一定位：返回 { element } 或 { x, y }。
    locate(target) {
      if (typeof target === "number") return { element: target };
      if (target !== null && typeof target === "object" && !isNode(target) && "x" in target && "y" in target) {
        return { x: target.x, y: target.y };
      }
      const selector = isNode(target) ? nodeSelector(target) : target;
      const nodes = this.findAll(selector);
      if (nodes.length === 0) throw fail("not_found", "找不到元素：" + JSON.stringify(selector));
      if (nodes.length > 1 && !isNode(target)) {
        throw fail("invalid_argument", "选择器匹配到 " + nodes.length + " 个元素：" + JSON.stringify(selector));
      }
      return { element: nodes[0].element };
    },
    // 起点在动作之前记下，动作引起的事件都在它之后。
    act(name, args) {
      const start = call("mark", {});
      return startFrom(call(name, Object.assign({ display: this.display }, args)), new Mark(this, start));
    },

    tap(target, options) {
      const t = this.locate(target);
      return this.act("tap", {
        element: t.element || 0, x: t.x || 0, y: t.y || 0,
        count: (options && options.count) || 1, long: !!(options && options.long),
      });
    },
    setText(target, text) {
      return this.act("setText", { element: this.locate(target).element, text: String(text) });
    },
    selectText(target, text, options) {
      const o = options || {};
      return this.act("selectText", {
        element: this.locate(target).element, text: String(text),
        prefix: o.prefix || "", suffix: o.suffix || "", selection: o.selection || "text",
      });
    },
    action(target, name) {
      return this.act("nodeAction", { element: this.locate(target).element, action: String(name) });
    },
    scroll(target, direction, pages) {
      const element = target === null || target === undefined ? 0 : this.locate(target).element;
      return this.act("scroll", { element: element, direction: String(direction), pages: pages || 1 });
    },
    swipe(fromX, fromY, toX, toY, options) {
      return this.act("swipe", { fromX, fromY, toX, toY, duration: (options && options.duration) || 300 });
    },
    type(text) {
      return this.act("type", { text: String(text) });
    },
    key(name) {
      return this.act("key", { key: String(name) });
    },
    launch(app) {
      return typeof app === "string" ? this.act("launch", { package: app, intent: "" })
        : this.act("launch", { package: app.package || "", intent: app.intent || "" });
    },
    moveTask(taskId) {
      return this.act("moveTask", { taskId });
    },
  };

  function log(...values) {
    call("log", { text: values.map((v) => (typeof v === "string" ? v : JSON.stringify(v))).join(" ") });
  }
  function display(image) {
    call("display", { image });
  }
  function sleep(ms) {
    call("sleep", { ms });
  }
  Object.assign(Screen.prototype, { log, display, sleep });

  // shell：与 exec 工具相同（agentd session），非零退出不抛错，返回 exec 的结果对象。
  // 只在用户开启 root shell 时挂到全局（由 __run 按每次运行的设置定义或删除）。
  function sh(command, options) {
    const o = options || {};
    return call("sh", { command: String(command), cwd: o.cwd || "", timeout: o.timeout || 0 });
  }

  // 文件：与文件工具相同（agentd 的 fileop helper），失败时抛错。
  globalThis.fs = {
    read(path) {
      return call("fs.read", { path: String(path) });
    },
    readBytes(path, offset, length) {
      return call("fs.readBytes", { path: String(path), offset: offset || 0, length: length || 32768 });
    },
    write(path, text, options) {
      return call("fs.write", { path: String(path), content: String(text), append: !!(options && options.append) });
    },
    edit(path, oldText, newText, count) {
      return call("fs.edit", { path: String(path), old: String(oldText), new: String(newText), expected_count: count || 1 });
    },
    stat(path) {
      return call("fs.stat", { path: String(path) });
    },
    list(path) {
      return call("fs.list", { path: String(path) });
    },
  };

  // 按名字调用任意工具（exec_js 除外），返回工具结果的对象；ok 为 false 时抛错。工具名和参数名见各工具的定义（DeviceTools 等）。
  function tool(name, args) {
    const r = call("tool", { name: String(name), args: args || {} });
    if (!r.ok) throw fail(r.error.code, r.error.message);
    return r;
  }
  globalThis.tool = tool;
  globalThis.apps = {
    list: (filter, includeSystem) => tool("app_list", { filter: filter || "", include_system: !!includeSystem }).apps,
    install: (path) => tool("app_install", { path: String(path) }),
    uninstall: (pkg) => tool("app_uninstall", { package: String(pkg) }),
  };
  globalThis.settings = {
    get: (namespace, key) => tool("settings_get", { namespace, key }).value,
    list: (namespace, filter) => tool("settings_list", { namespace, filter: filter || "" }).settings,
    put: (namespace, key, value) => tool("settings_put", { namespace, key, value: String(value) }),
  };
  globalThis.device = { info: () => tool("device_info") };
  globalThis.notifications = {
    list: () => tool("notification_list").notifications,
    open: (key) => tool("notification_open", { key }),
    dismiss: (key) => tool("notification_dismiss", { key }),
  };
  globalThis.clipboard = {
    get: () => tool("clipboard_get").text,
    set: (text) => tool("clipboard_set", { text: String(text) }),
  };

  // 模型常省略 screen.，所以虚拟屏幕的方法也挂到全局。
  const screen = new Screen(-1);
  globalThis.screen = screen;
  for (const name of Object.keys(Screen.prototype)) {
    if (!(name in globalThis)) globalThis[name] = Screen.prototype[name].bind(screen);
  }
  // 执行模型的代码（间接 eval，全局作用域），返回 JsHost 格式的结果：值是最后一个表达式转成的 JSON，
  // shell 为 false 时脚本里没有 sh（运行时跨调用保留，所以每次都要处理）；
  // 出错时是 error（code 默认 failed）和 stack。停止和超时的中断捕获不到，由宿主处理。
  // 模型常在顶层写 return：这种语法错误发生在执行之前，改为包成函数执行，函数的返回值作为结果。
  globalThis.__run = function (code, shell) {
    if (shell) globalThis.sh = sh;
    else delete globalThis.sh;
    chains = [];
    let value;
    try {
      try {
        value = globalThis.eval(code);
      } catch (e) {
        if (!(e instanceof SyntaxError) || !/return not in a function/.test(e.message)) throw e;
        value = globalThis.eval("(function () {\n" + code + "\n})()");
      }
      if (chains.length > 0) {
        throw fail("invalid_argument", "有 " + chains.length + " 个起点挂了 when/timeout 但没有调用 wait()，没有执行等待");
      }
    } catch (e) {
      // 普通 Error 只取 message，TypeError 等保留类型名。
      const message = e instanceof Error && e.name === "Error" ? e.message : String(e);
      const error = { code: (e && typeof e.code === "string" && e.code) || "failed", message };
      return JSON.stringify({ ok: false, error, stack: (e && e.stack) || "" });
    }
    return JSON.stringify({ ok: true, value: __result(value) });
  };

  // 值转成 JSON；转不了时用字符串。
  globalThis.__result = function (value) {
    if (value === undefined) return undefined;
    try {
      return JSON.stringify(value);
    } catch (e) {
      return String(value);
    }
  };
})();
