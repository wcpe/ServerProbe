package top.wcpe.mc.plugin.serverprobe.command

import top.wcpe.mc.plugin.serverprobe.api.model.StartupProfile
import top.wcpe.mc.plugin.serverprobe.api.model.ThreadStackProfile
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

/**
 * 火焰图 & 瀑布图导出器(M5)。
 *
 * 从 [StartupProfile] 的 agent 增强数据生成 **自包含** HTML 文件(所有 CSS/JS 内联,无外部 CDN 依赖),含:
 * - **真正的多层、多线程火焰图**:由 [StartupProfile.threadStacks] 的折叠栈逐层并树而成
 *   (宽度=该调用路径采样占比、纵轴=调用深度;支持线程切换、缩放/搜索/悬浮);
 * - **嵌套时间线**:由 [StartupProfile.timelineEvents] 按区间包含关系分泳道渲染(父区间在下层、子区间在上层),
 *   X 为相对 premain 的真实时间;直观体现"enable 区间包含其内部 register/config/command 子区间"的父子耗时归属。
 *
 * 输出目录为 `data/flamegraph/`(相对于插件数据目录)。
 */
object FlamegraphExporter {

    /**
     * 导出火焰图 HTML 文件。
     *
     * @param profile 启动画像(必须已挂载 agent,否则无折叠栈/时间线数据)。
     * @param outputDir 输出目录(不存在时自动创建)。
     * @return 生成的 HTML 文件。
     */
    fun export(profile: StartupProfile, outputDir: File): File {
        outputDir.mkdirs()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
        val file = File(outputDir, "flamegraph-$timestamp.html")
        file.writeText(generateHtml(profile))
        return file
    }

    /**
     * 生成完整自包含 HTML。
     */
    private fun generateHtml(profile: StartupProfile): String {
        val flamegraphThreads = buildThreadTrees(profile)
        val waterfallData = buildWaterfallData(profile)
        val reportHtml = buildReportHtml(profile)
        val totalMs = profile.totalMs
        val serverId = escapeJson(profile.serverId)
        val mcVersion = escapeJson(profile.mcVersion)
        val threadCount = profile.threadStacks?.size ?: 0
        val totalSamples = profile.threadStacks.orEmpty().sumOf { tp -> tp.stacks.sumOf { it.sampleCount } }
        val eventCount = profile.timelineEvents?.size ?: 0
        val sampleIntervalMs = profile.sampleIntervalMs ?: 0L

        return """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>ServerProbe Flamegraph - $serverId</title>
<style>
* { margin: 0; padding: 0; box-sizing: border-box; }
body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: #1e1e2e; color: #cdd6f4; }
.header { background: #181825; padding: 16px 24px; border-bottom: 1px solid #313244; }
.header h1 { font-size: 18px; color: #cba6f7; margin-bottom: 8px; }
.meta { display: flex; gap: 24px; font-size: 13px; color: #a6adc8; flex-wrap: wrap; }
.meta span { display: inline-flex; align-items: center; gap: 4px; }
.meta .value { color: #f5e0dc; font-weight: 600; }
.tabs { display: flex; background: #181825; border-bottom: 1px solid #313244; padding: 0 24px; }
.tab { padding: 10px 20px; cursor: pointer; font-size: 14px; color: #a6adc8; border-bottom: 2px solid transparent; transition: all 0.2s; user-select: none; }
.tab:hover { color: #cdd6f4; background: #1e1e2e; }
.tab.active { color: #89b4fa; border-bottom-color: #89b4fa; }
.panel { display: none; }
.panel.active { display: block; }
.toolbar { display: flex; align-items: center; gap: 12px; padding: 8px 24px; background: #11111b; border-bottom: 1px solid #313244; flex-wrap: wrap; }
.toolbar input, .toolbar select { background: #313244; border: 1px solid #45475a; border-radius: 4px; color: #cdd6f4; padding: 6px 12px; font-size: 13px; outline: none; }
.toolbar input { width: 280px; }
.toolbar input:focus, .toolbar select:focus { border-color: #89b4fa; }
.toolbar label { font-size: 12px; color: #a6adc8; }
.toolbar .info { font-size: 12px; color: #6c7086; margin-left: auto; }
#breadcrumb { padding: 6px 24px; font-size: 12px; color: #a6adc8; background: #11111b; border-bottom: 1px solid #313244; }
#breadcrumb span { color: #89b4fa; cursor: pointer; }
#breadcrumb span:hover { text-decoration: underline; }
.empty-hint { padding: 40px 24px; color: #6c7086; font-size: 14px; }
.toolbar button { background: #313244; border: 1px solid #45475a; border-radius: 4px; color: #cdd6f4; padding: 6px 12px; font-size: 13px; cursor: pointer; }
.toolbar button:hover { border-color: #89b4fa; color: #fff; }
.report { padding: 16px 24px; }
.report h2 { font-size: 15px; color: #89b4fa; margin: 18px 0 8px; }
.report h2:first-child { margin-top: 0; }
.report .hint { font-size: 12px; color: #6c7086; margin: 4px 0 10px; }
.report table { border-collapse: collapse; width: 100%; max-width: 1100px; font-size: 13px; margin-bottom: 6px; }
.report th, .report td { text-align: left; padding: 6px 10px; border-bottom: 1px solid #2a2a3c; }
.report th { color: #a6adc8; font-weight: 600; background: #181825; }
.report td.num { text-align: right; font-variant-numeric: tabular-nums; color: #f5e0dc; }
.report td.frame { font-family: 'Cascadia Code','Fira Code',monospace; color: #cdd6f4; word-break: break-all; }
.report .bar { display: inline-block; height: 10px; background: #f38ba8; border-radius: 2px; vertical-align: middle; margin-left: 6px; }
.report .tag { display: inline-block; padding: 1px 7px; border-radius: 3px; font-size: 11px; color: #1e1e2e; font-weight: 600; }
.report .empty { color: #6c7086; font-size: 13px; }
canvas { display: block; cursor: pointer; }
#tooltip { position: fixed; background: #313244; border: 1px solid #45475a; border-radius: 6px; padding: 10px 14px; font-size: 12px; pointer-events: none; display: none; z-index: 1000; max-width: 520px; box-shadow: 0 4px 12px rgba(0,0,0,0.4); }
#tooltip .tt-name { color: #f5e0dc; font-weight: 600; margin-bottom: 4px; word-break: break-all; font-family: 'Cascadia Code', 'Fira Code', monospace; }
#tooltip .tt-detail { color: #a6adc8; }
</style>
</head>
<body>

<div class="header">
<h1>ServerProbe Startup Flamegraph</h1>
<div class="meta">
<span>Server: <span class="value">$serverId</span></span>
<span>MC: <span class="value">$mcVersion</span></span>
<span>启动耗时: <span class="value">${totalMs}ms</span></span>
<span>采样线程: <span class="value">$threadCount</span></span>
<span>采样总数: <span class="value">$totalSamples</span></span>
<span>时间线事件: <span class="value">$eventCount</span></span>
</div>
</div>

<div class="tabs">
<div class="tab active" onclick="switchTab('report')">分析报告</div>
<div class="tab" onclick="switchTab('flamegraph')">火焰图</div>
<div class="tab" onclick="switchTab('waterfall')">时间线</div>
</div>

<div id="report-panel" class="panel active">
<div class="report">$reportHtml</div>
</div>

<div id="flamegraph-panel" class="panel">
<div class="toolbar">
<label for="thread-select">线程:</label>
<select id="thread-select"></select>
<input type="text" id="search" placeholder="搜索帧名(如 org.bukkit, onEnable)..." autocomplete="off">
<button id="reset-view" type="button">重置视图</button>
<div class="info">滚轮缩放 | 拖拽平移 | 左键下钻 | 右键返回上层</div>
</div>
<div id="breadcrumb"></div>
<div id="flamegraph-empty" class="empty-hint" style="display:none;">无折叠栈数据(需挂载启动 agent 并完成一次采样)。</div>
<canvas id="flamegraph-canvas"></canvas>
</div>

<div id="waterfall-panel" class="panel">
<canvas id="waterfall-canvas"></canvas>
</div>

<div id="tooltip">
<div class="tt-name"></div>
<div class="tt-detail"></div>
</div>

<script>
var TAB_NAMES = ['report', 'flamegraph', 'waterfall'];
function switchTab(name) {
document.querySelectorAll('.tab').forEach(function(t, i) {
t.classList.toggle('active', TAB_NAMES[i] === name);
});
document.getElementById('report-panel').classList.toggle('active', name === 'report');
document.getElementById('flamegraph-panel').classList.toggle('active', name === 'flamegraph');
document.getElementById('waterfall-panel').classList.toggle('active', name === 'waterfall');
if (name === 'waterfall') {
waterfallRenderer.resize();
waterfallRenderer.draw();
} else if (name === 'flamegraph' && flamegraphRenderer) {
flamegraphRenderer.resize();
flamegraphRenderer.draw();
}
}

// 每个线程一棵火焰图树:[{name, root:{name,value,children}}, ...]
var FLAMEGRAPH_THREADS = $flamegraphThreads;
var WATERFALL_DATA = $waterfallData;
var SAMPLE_INTERVAL_MS = $sampleIntervalMs;

function escapeHtml(s) {
var d = document.createElement('div');
d.appendChild(document.createTextNode(s));
return d.innerHTML;
}

// === 火焰图渲染器(Canvas,多层 + 多线程,支持滚轮缩放 / 拖拽平移) ===
var FlamegraphRenderer = (function() {
function R(canvasId, threads) {
this.canvas = document.getElementById(canvasId);
this.ctx = this.canvas.getContext('2d');
this.dpr = window.devicePixelRatio || 1;
this.barHeight = 20;
this.padding = 1;
this.searchTerm = '';
this.threads = threads;
// 默认优先选 "Server thread"(启动主瓶颈线程),否则取第一个
this.threadIdx = 0;
for (var i = 0; i < threads.length; i++) {
if (threads[i].name.indexOf('Server thread') === 0) { this.threadIdx = i; break; }
}
this.root = threads[this.threadIdx].root;
this.currentRoot = this.root;
this.zoomStack = [this.root];
// 视口:占 currentRoot 全宽的比例区间 [viewStart, viewEnd];滚轮改变其宽度、拖拽平移其位置
this.viewStart = 0;
this.viewEnd = 1;
this._drag = null;
this._bind();
this.resize();
this.draw();
this._updateBreadcrumb();
}

R.prototype._bind = function() {
var self = this;
this.canvas.addEventListener('mousemove', function(e) { self._onMouseMove(e); });
this.canvas.addEventListener('mousedown', function(e) { self._onMouseDown(e); });
this.canvas.addEventListener('mouseup', function(e) { self._onMouseUp(e); });
this.canvas.addEventListener('mouseleave', function() { self._drag = null; self._hideTooltip(); });
this.canvas.addEventListener('contextmenu', function(e) { self._onRightClick(e); });
this.canvas.addEventListener('wheel', function(e) { self._onWheel(e); }, { passive: false });
window.addEventListener('resize', function() { self.resize(); self.draw(); });
};

R.prototype._resetView = function() { this.viewStart = 0; this.viewEnd = 1; };

R.prototype.setThread = function(idx) {
this.threadIdx = idx;
this.root = this.threads[idx].root;
this.currentRoot = this.root;
this.zoomStack = [this.root];
this._resetView();
this.resize();
this.draw();
this._updateBreadcrumb();
this._hideTooltip();
};

// 重置:回到当前线程根 + 全宽视口
R.prototype.resetAll = function() {
this.currentRoot = this.root;
this.zoomStack = [this.root];
this._resetView();
this.resize();
this.draw();
this._updateBreadcrumb();
this._hideTooltip();
};

R.prototype.resize = function() {
var w = this.canvas.parentElement.clientWidth;
var rows = this._maxDepth(this.currentRoot) + 1;
var h = Math.max(240, rows * (this.barHeight + this.padding) + 30);
this.canvas.style.width = w + 'px';
this.canvas.style.height = h + 'px';
this.canvas.width = w * this.dpr;
this.canvas.height = h * this.dpr;
this.ctx.scale(this.dpr, this.dpr);
this.logicalW = w;
this.logicalH = h;
};

// 比例 → 屏幕 X(经视口换算)
R.prototype._sx = function(xFrac) {
var vw = this.viewEnd - this.viewStart;
return (xFrac - this.viewStart) / vw * this.logicalW;
};
// 比例宽 → 屏幕宽
R.prototype._sw = function(wFrac) {
return wFrac / (this.viewEnd - this.viewStart) * this.logicalW;
};

// 自当前缩放根递归收集可见节点;坐标用"占 currentRoot 全宽的比例"(xFrac/wFrac)。
// 当前视口下屏幕宽 < 0.3px 的子树整体跳过,使可见节点数随缩放自适应、保持高帧率。
R.prototype._flattenVisible = function(node, depth, xFrac, arr) {
var rootVal = this.currentRoot.value || 1;
var wFrac = node.value / rootVal;
if (this._sw(wFrac) < 0.3) return;
arr.push({ node: node, depth: depth, xFrac: xFrac, wFrac: wFrac });
var children = node.children || [];
var cx = xFrac;
for (var i = 0; i < children.length; i++) {
this._flattenVisible(children[i], depth + 1, cx, arr);
cx += children[i].value / rootVal;
}
};

R.prototype._maxDepth = function(root) {
var stack = [{ node: root, depth: 0 }];
var maxDepth = 0;
while (stack.length > 0) {
var item = stack.pop();
maxDepth = Math.max(maxDepth, item.depth);
var children = item.node.children || [];
for (var i = 0; i < children.length; i++) {
stack.push({ node: children[i], depth: item.depth + 1 });
}
}
return maxDepth;
};

// 按帧名 hash 映射到暖色区间(Brendan Gregg 风格);命中搜索词则高亮。
R.prototype._color = function(name) {
if (this.searchTerm && name.toLowerCase().indexOf(this.searchTerm.toLowerCase()) >= 0) {
return 'rgb(230,110,40)';
}
var h = 0;
for (var i = 0; i < name.length; i++) { h = (h * 31 + name.charCodeAt(i)) & 0x7fffffff; }
var hue = 5 + (h % 55);
var sat = 62 + (h % 18);
var lum = 46 + (h % 14);
return 'hsl(' + hue + ',' + sat + '%,' + lum + '%)';
};

R.prototype.draw = function() {
var ctx = this.ctx;
ctx.save();
ctx.setTransform(this.dpr, 0, 0, this.dpr, 0, 0);
ctx.clearRect(0, 0, this.logicalW, this.logicalH);
var items = [];
this._flattenVisible(this.currentRoot, 0, 0, items);
for (var i = 0; i < items.length; i++) {
var item = items[i];
var sw = this._sw(item.wFrac);
if (sw < 0.4) continue;
var sx = this._sx(item.xFrac);
if (sx > this.logicalW || sx + sw < 0) continue; // 视口外剔除
var y = this.logicalH - (item.depth + 1) * (this.barHeight + this.padding) - 20;
// 记录屏幕坐标供命中测试
item.screenX = sx; item.screenW = sw; item.screenY = y;
ctx.fillStyle = this._color(item.node.name);
ctx.fillRect(sx, y, Math.max(1, sw - 1), this.barHeight);
if (sw > 32) {
ctx.fillStyle = '#1e1e2e';
ctx.font = '11px "Cascadia Code","Fira Code",monospace';
ctx.save();
ctx.beginPath();
ctx.rect(Math.max(0, sx), y, sw - 1, this.barHeight);
ctx.clip();
ctx.fillText(this._shortName(item.node.name), Math.max(0, sx) + 4, y + 14);
ctx.restore();
}
}
ctx.restore();
this._items = items;
};

// 帧标识较长(全类名#方法),条上只显示"类简名#方法"以省空间;tooltip 仍给全名。
R.prototype._shortName = function(name) {
var hash = name.indexOf('#');
if (hash < 0) return name;
var cls = name.substring(0, hash);
var method = name.substring(hash);
var dot = cls.lastIndexOf('.');
return (dot >= 0 ? cls.substring(dot + 1) : cls) + method;
};

R.prototype._hitTest = function(e) {
var rect = this.canvas.getBoundingClientRect();
var mx = e.clientX - rect.left;
var my = e.clientY - rect.top;
if (!this._items) return null;
for (var i = this._items.length - 1; i >= 0; i--) {
var item = this._items[i];
if (item.screenX == null) continue; // 未绘制(被剔除)的节点不参与命中
if (mx >= item.screenX && mx <= item.screenX + item.screenW && my >= item.screenY && my <= item.screenY + this.barHeight) {
return item;
}
}
return null;
};

R.prototype._showTooltip = function(item, e) {
var tip = document.getElementById('tooltip');
var rootVal = this.currentRoot.value || 1;
var pct = (item.node.value / rootVal * 100).toFixed(2);
var detail = item.node.value + ' samples (' + pct + '% of view) | depth=' + item.depth;
if (SAMPLE_INTERVAL_MS > 0) {
detail += ' | ~' + (item.node.value * SAMPLE_INTERVAL_MS) + 'ms';
}
tip.querySelector('.tt-name').textContent = item.node.name;
tip.querySelector('.tt-detail').textContent = detail;
tip.style.display = 'block';
tip.style.left = (e.clientX + 12) + 'px';
tip.style.top = (e.clientY + 12) + 'px';
};

R.prototype._hideTooltip = function() {
document.getElementById('tooltip').style.display = 'none';
};

R.prototype._onMouseDown = function(e) {
var rect = this.canvas.getBoundingClientRect();
this._drag = { x: e.clientX - rect.left, moved: false, vs: this.viewStart, ve: this.viewEnd };
};

R.prototype._onMouseUp = function(e) {
var drag = this._drag;
this._drag = null;
if (drag && drag.moved) return; // 拖拽收尾,不当作点击下钻
var hit = this._hitTest(e);
if (hit && hit.node !== this.currentRoot && (hit.node.children || []).length > 0) {
this.currentRoot = hit.node;
this.zoomStack.push(hit.node);
this._resetView();
this.resize();
this.draw();
this._updateBreadcrumb();
this._hideTooltip();
}
};

R.prototype._onMouseMove = function(e) {
if (this._drag) {
var rect = this.canvas.getBoundingClientRect();
var dx = (e.clientX - rect.left) - this._drag.x;
if (Math.abs(dx) > 3) this._drag.moved = true;
if (this._drag.moved) {
var vw = this._drag.ve - this._drag.vs;
var dFrac = (dx / this.logicalW) * vw;
var ns = Math.max(0, Math.min(1 - vw, this._drag.vs - dFrac));
this.viewStart = ns; this.viewEnd = ns + vw;
this._hideTooltip();
this.canvas.style.cursor = 'grabbing';
this.draw();
}
return;
}
var hit = this._hitTest(e);
if (hit) {
this._showTooltip(hit, e);
this.canvas.style.cursor = 'pointer';
} else {
this._hideTooltip();
this.canvas.style.cursor = 'default';
}
};

// 滚轮:以光标处为锚点做水平缩放(上滚放大、下滚缩小)
R.prototype._onWheel = function(e) {
e.preventDefault();
var rect = this.canvas.getBoundingClientRect();
var mx = e.clientX - rect.left;
var vw = this.viewEnd - this.viewStart;
var cursorFrac = this.viewStart + (mx / this.logicalW) * vw;
var factor = e.deltaY < 0 ? 0.82 : 1.22;
var newVw = Math.min(1, Math.max(0.0008, vw * factor));
var ns = Math.max(0, Math.min(1 - newVw, cursorFrac - (mx / this.logicalW) * newVw));
this.viewStart = ns;
this.viewEnd = ns + newVw;
this._hideTooltip();
this.draw();
};

R.prototype._onRightClick = function(e) {
e.preventDefault();
if (this.zoomStack.length > 1) {
this.zoomStack.pop();
this.currentRoot = this.zoomStack[this.zoomStack.length - 1];
this._resetView();
this.resize();
this.draw();
this._updateBreadcrumb();
this._hideTooltip();
}
};

R.prototype._updateBreadcrumb = function() {
var bc = document.getElementById('breadcrumb');
if (this.zoomStack.length <= 1) {
bc.innerHTML = '缩放路径: <span data-idx="0">' + escapeHtml(this.currentRoot.name) + '</span>';
} else {
var parts = [];
for (var i = 0; i < this.zoomStack.length; i++) {
parts.push('<span data-idx="' + i + '">' + escapeHtml(this.zoomStack[i].name) + '</span>');
}
bc.innerHTML = '缩放路径: ' + parts.join(' &gt; ');
}
var self = this;
bc.querySelectorAll('span').forEach(function(el) {
el.onclick = function() {
var idx = parseInt(this.getAttribute('data-idx'));
self.zoomStack = self.zoomStack.slice(0, idx + 1);
self.currentRoot = self.zoomStack[idx];
self._resetView();
self.resize();
self.draw();
self._updateBreadcrumb();
};
});
};

R.prototype.setSearch = function(term) {
this.searchTerm = term;
this.draw();
};

return R;
})();

// === 时间线渲染器(Canvas,按区间包含关系做嵌套泳道) ===
var WaterfallRenderer = (function() {
function R(canvasId) {
this.canvas = document.getElementById(canvasId);
this.ctx = this.canvas.getContext('2d');
this.dpr = window.devicePixelRatio || 1;
this.events = WATERFALL_DATA.slice();
this.laneH = 20;
this.laneGap = 2;
this._computeDepths();
this._bind();
this.resize();
this.draw();
}

R.prototype._bind = function() {
var self = this;
this.canvas.addEventListener('mousemove', function(e) { self._onMouseMove(e); });
this.canvas.addEventListener('mouseleave', function() { self._hideTooltip(); });
window.addEventListener('resize', function() { self.resize(); self.draw(); });
};

// 按 start 升序、end 降序排序后用栈求嵌套深度:深度 = 当前仍"未结束"的祖先数。
// 真实调用区间天然嵌套(enable 区间包含其内部的 register/config/command 子区间),据此分泳道还原父子层级,
// 不再把子区间与父区间平铺成并列行(消除"子项与父项并列"的重复计费视觉误导)。
R.prototype._computeDepths = function() {
this.events.sort(function(a, b) { return (a.start - b.start) || (b.end - a.end); });
var stack = [];
var maxDepth = 0;
for (var i = 0; i < this.events.length; i++) {
var ev = this.events[i];
// 弹出所有"在 ev 开始前就已结束"的祖先(不再包含 ev);剩余栈即 ev 的祖先链,深度=链长
while (stack.length > 0 && stack[stack.length - 1].end <= ev.start) stack.pop();
ev._depth = stack.length;
if (ev._depth > maxDepth) maxDepth = ev._depth;
stack.push(ev);
}
this.maxDepth = maxDepth;
};

R.prototype.resize = function() {
var w = this.canvas.parentElement.clientWidth;
this.topPad = 16;
this.botPad = 48;
var lanes = this.maxDepth + 1;
var h = (this.events.length === 0)
? 120
: this.topPad + lanes * (this.laneH + this.laneGap) + this.botPad;
this.canvas.style.width = w + 'px';
this.canvas.style.height = h + 'px';
this.canvas.width = w * this.dpr;
this.canvas.height = h * this.dpr;
this.ctx.scale(this.dpr, this.dpr);
this.logicalW = w;
this.logicalH = h;
};

R.prototype._typeColor = function(type) {
var colors = {
enable: '#89b4fa',
load: '#a6e3a1',
library: '#fab387',
worldCreate: '#f9e2af',
configLoad: '#cba6f7',
eventRegister: '#94e2d5',
commandRegister: '#f5c2e7'
};
return colors[type] || '#bac2de';
};

R.prototype._eventLabel = function(type) {
var labels = {
enable: '插件启用',
load: '插件加载',
library: '库下载',
worldCreate: '世界创建',
configLoad: '配置加载',
eventRegister: '事件注册',
commandRegister: '命令注册'
};
return labels[type] || type;
};

R.prototype.draw = function() {
var ctx = this.ctx;
ctx.save();
ctx.setTransform(this.dpr, 0, 0, this.dpr, 0, 0);
ctx.clearRect(0, 0, this.logicalW, this.logicalH);
if (this.events.length === 0) {
ctx.fillStyle = '#6c7086';
ctx.font = '14px sans-serif';
ctx.fillText('无时间线事件数据(需挂载启动 agent)', 20, 40);
ctx.restore();
return;
}
var leftMargin = 8, rightMargin = 20;
var chartW = this.logicalW - leftMargin - rightMargin;
var axisY = this.logicalH - this.botPad;
var totalNs = 0;
for (var i = 0; i < this.events.length; i++) {
totalNs = Math.max(totalNs, this.events[i].end);
}
if (totalNs <= 0) totalNs = 1;
var niceNs = this._niceNum(totalNs);
var tickCount = Math.max(5, Math.min(15, Math.floor(chartW / 90)));
var tickStep = niceNs / tickCount;
// 纵向网格线 + 底部时间刻度(0 点 = premain)
ctx.font = '11px monospace';
ctx.textAlign = 'center';
for (var t = 0; t <= niceNs + tickStep * 0.5; t += tickStep) {
var gx = leftMargin + (t / niceNs) * chartW;
ctx.strokeStyle = '#26263a';
ctx.lineWidth = 1;
ctx.beginPath();
ctx.moveTo(gx, this.topPad);
ctx.lineTo(gx, axisY);
ctx.stroke();
ctx.fillStyle = '#a6adc8';
ctx.fillText(this._fmtTime(t), gx, axisY + 16);
}
ctx.textAlign = 'left';
// 事件条:按嵌套深度分泳道,X 为相对 premain 的真实时间,父区间在下层、子区间在上层
this._drawnBars = [];
for (var i = 0; i < this.events.length; i++) {
var ev = this.events[i];
var x = leftMargin + (ev.start / niceNs) * chartW;
var w = Math.max(2, ((ev.end - ev.start) / niceNs) * chartW);
var y = this.topPad + ev._depth * (this.laneH + this.laneGap);
ctx.fillStyle = this._typeColor(ev.type);
ctx.fillRect(x, y, w, this.laneH);
if (w > 36) {
ctx.fillStyle = '#11111b';
ctx.font = '11px sans-serif';
ctx.save();
ctx.beginPath();
ctx.rect(x, y, w, this.laneH);
ctx.clip();
ctx.fillText(ev.name, x + 4, y + 14);
ctx.restore();
}
this._drawnBars.push({ x: x, y: y, w: w, h: this.laneH, ev: ev });
}
// 图例
ctx.fillStyle = '#a6adc8';
ctx.font = '12px sans-serif';
ctx.fillText('事件类型:', leftMargin, this.logicalH - 8);
var types = ['enable','load','library','worldCreate','configLoad','eventRegister','commandRegister'];
var lx = leftMargin + 70;
for (var i = 0; i < types.length; i++) {
ctx.fillStyle = this._typeColor(types[i]);
ctx.fillRect(lx, this.logicalH - 18, 12, 12);
ctx.fillStyle = '#cdd6f4';
ctx.font = '11px sans-serif';
ctx.fillText(this._eventLabel(types[i]), lx + 16, this.logicalH - 8);
lx += ctx.measureText(this._eventLabel(types[i])).width + 32;
}
ctx.restore();
};

R.prototype._niceNum = function(n) {
var exp = Math.floor(Math.log10(n));
var f = n / Math.pow(10, exp);
var nf;
if (f <= 1) nf = 1;
else if (f <= 2) nf = 2;
else if (f <= 5) nf = 5;
else nf = 10;
return nf * Math.pow(10, exp);
};

R.prototype._fmtTime = function(ns) {
var ms = ns / 1000000;
if (ms < 1000) return ms.toFixed(0) + 'ms';
return (ms / 1000).toFixed(2) + 's';
};

R.prototype._onMouseMove = function(e) {
var rect = this.canvas.getBoundingClientRect();
var mx = e.clientX - rect.left;
var my = e.clientY - rect.top;
var hit = null;
if (this._drawnBars) {
for (var i = this._drawnBars.length - 1; i >= 0; i--) {
var b = this._drawnBars[i];
if (mx >= b.x && mx <= b.x + b.w && my >= b.y && my <= b.y + b.h) {
hit = b;
break;
}
}
}
if (hit) {
var tip = document.getElementById('tooltip');
var durMs = ((hit.ev.end - hit.ev.start) / 1000000).toFixed(3);
tip.querySelector('.tt-name').textContent = this._eventLabel(hit.ev.type) + ': ' + hit.ev.name;
tip.querySelector('.tt-detail').textContent = '耗时: ' + durMs + 'ms | 开始: ' + this._fmtTime(hit.ev.start) + ' | 结束: ' + this._fmtTime(hit.ev.end) + ' | 嵌套深度: ' + hit.ev._depth;
tip.style.display = 'block';
tip.style.left = (e.clientX + 12) + 'px';
tip.style.top = (e.clientY + 12) + 'px';
}
this.canvas.style.cursor = 'default';
};

R.prototype._hideTooltip = function() {
document.getElementById('tooltip').style.display = 'none';
};

return R;
})();

// 初始化:火焰图(若有线程数据)+ 线程下拉 + 搜索;瀑布图。
var flamegraphRenderer = null;
if (FLAMEGRAPH_THREADS.length > 0) {
flamegraphRenderer = new FlamegraphRenderer('flamegraph-canvas', FLAMEGRAPH_THREADS);
var sel = document.getElementById('thread-select');
for (var i = 0; i < FLAMEGRAPH_THREADS.length; i++) {
var opt = document.createElement('option');
opt.value = i;
var total = (FLAMEGRAPH_THREADS[i].root.value || 0);
var est = SAMPLE_INTERVAL_MS > 0 ? ', ~' + (total * SAMPLE_INTERVAL_MS) + 'ms' : '';
opt.textContent = FLAMEGRAPH_THREADS[i].name + ' (' + total + ' 采样' + est + ')';
sel.appendChild(opt);
}
sel.value = flamegraphRenderer.threadIdx;
sel.addEventListener('change', function() { flamegraphRenderer.setThread(parseInt(this.value)); });
var searchTimer = null;
document.getElementById('search').addEventListener('input', function() {
var val = this.value;
clearTimeout(searchTimer);
searchTimer = setTimeout(function() { flamegraphRenderer.setSearch(val); }, 150);
});
document.getElementById('reset-view').addEventListener('click', function() { flamegraphRenderer.resetAll(); });
} else {
document.getElementById('flamegraph-empty').style.display = 'block';
document.getElementById('thread-select').style.display = 'none';
document.getElementById('search').style.display = 'none';
document.getElementById('reset-view').style.display = 'none';
}

var waterfallRenderer = new WaterfallRenderer('waterfall-canvas');
</script>
</body>
</html>"""
    }

    /**
     * 把每个线程的折叠栈并成一棵火焰图树,序列化为 `[{name, root}, ...]` JSON。
     *
     * 每条折叠栈([top.wcpe.mc.plugin.serverprobe.api.model.FoldedStack])自栈底到栈顶逐层并入树:
     * 沿途各节点的 value 累加该栈命中数,故根节点 value=该线程总采样数,每个节点 value=经过该调用路径的采样数。
     * 这正是真正多层火焰图所需的数据形态。
     */
    private fun buildThreadTrees(profile: StartupProfile): String {
        val threads = profile.threadStacks
        if (threads.isNullOrEmpty()) {
            return "[]"
        }
        // 按线程采样总数降序:最忙的线程(常即卡顿所在,如并行下载线程)排在下拉首位,便于一眼定位
        return threads
            .sortedByDescending { tp -> tp.stacks.sumOf { it.sampleCount } }
            .joinToString(",", "[", "]") { tp -> threadTreeJson(tp) }
    }

    /**
     * 把单个线程的折叠栈集合并成树并序列化为 `{"name":...,"root":{...}}`。
     */
    private fun threadTreeJson(tp: ThreadStackProfile): String {
        val root = FrameNode("root")
        tp.stacks.forEach { stack ->
            root.value += stack.sampleCount
            var node = root
            stack.frames.forEach { frame ->
                node = node.child(frame)
                node.value += stack.sampleCount
            }
        }
        return "{\"name\":\"${escapeJson(tp.threadName)}\",\"root\":${root.toJson()}}"
    }

    /**
     * 构建瀑布图数据 JSON。
     *
     * 时刻已是相对 premain 的纳秒偏移,直接透传(0 点 = premain),如实反映 premain→各 hook 的真实时间线
     * (含 premain 到首个 hook 之间的早期耗时)。
     */
    private fun buildWaterfallData(profile: StartupProfile): String {
        val events = profile.timelineEvents
        if (events.isNullOrEmpty()) return "[]"
        return events.joinToString(",", "[", "]") { ev ->
            "{\"type\":\"${escapeJson(ev.type)}\",\"name\":\"${escapeJson(ev.name)}\"," +
                "\"start\":${ev.startNanos},\"end\":${ev.endNanos}}"
        }
    }

    /**
     * 构建"分析报告"面板 HTML(静态表格,在 Kotlin 侧计算好,前端直接展示)。
     *
     * 目标是让运维**一眼看出**启动时间花在哪:
     * - **插件耗时 Top-N(load + enable 合并)**:回答"哪个插件占用开启时间"。合并 load 与 enable 至关重要——
     *   TabooLib 等框架的重活(依赖下载、初始化)发生在 `loadPlugin`(load 阶段),仅按 enable 排名会漏掉它。
     * - **库下载 / 世界创建 Top-N**。
     * - **配置 / 事件 / 命令注册 Top-N**(均为 enable 的子组成)。
     * - **采样热点 Top-N(跨线程,估算墙钟,滤除空闲等待)**:回答"卡在哪段代码"——这是唯一能暴露**非 hook**
     *   瓶颈(如在并行下载线程上闷头卡数分钟的 TabooLib/插件依赖下载)的视图。
     */
    private fun buildReportHtml(profile: StartupProfile): String {
        val totalMs = profile.totalMs.coerceAtLeast(1)
        val intervalMs = profile.sampleIntervalMs ?: 0L
        val sb = StringBuilder()

        sb.append("<h2>启动概要</h2><div class=\"hint\">")
        sb.append("总耗时 ").append(profile.totalMs).append("ms")
        if (profile.agentAttached) {
            sb.append(" · 采样线程 ").append(profile.threadStacks?.size ?: 0)
            sb.append(" · 采样周期 ").append(intervalMs).append("ms")
            sb.append(" · 时间线事件 ").append(profile.timelineEvents?.size ?: 0)
        } else {
            sb.append(" · 未挂载启动 agent(仅基础数据;加 -javaagent:plugins/ServerProbe.jar 可获库下载/线程采样/火焰图)")
        }
        sb.append("</div>")

        sb.append(buildPluginCostTable(profile, totalMs))
        sb.append(buildNamedTable("库下载耗时 Top-N", profile.libraryTimings?.map { it.name to it.loadMs }, totalMs))
        sb.append(buildNamedTable("世界创建耗时 Top-N", profile.worldTimings.map { it.name to it.loadMs }.filter { it.second > 0 }, totalMs))
        sb.append(buildNamedTable("配置加载耗时 Top-N", profile.configTimings?.map { it.name to it.costMs }, totalMs))
        sb.append(buildNamedTable("事件注册耗时 Top-N", profile.eventTimings?.map { it.name to it.costMs }, totalMs))
        sb.append(buildNamedTable("命令注册耗时 Top-N", profile.commandTimings?.map { it.name to it.costMs }, totalMs))
        sb.append(buildSamplingHotspots(profile, intervalMs))
        return sb.toString()
    }

    /**
     * 插件耗时榜:挂载 agent 时用 load+enable 合并实测(揭示 load 阶段的下载/初始化大头),否则退回日志近似。
     */
    private fun buildPluginCostTable(profile: StartupProfile, totalMs: Long): String {
        val load = (profile.agentPluginLoadTimings ?: emptyList()).associate { it.name to it.enableMs }
        val enable = (profile.agentPluginEnableTimings ?: emptyList()).associate { it.name to it.enableMs }
        if (load.isNotEmpty() || enable.isNotEmpty()) {
            val names = LinkedHashSet<String>().apply { addAll(load.keys); addAll(enable.keys) }
            val rows = names
                .map { Triple(it, load[it] ?: 0L, enable[it] ?: 0L) }
                .sortedByDescending { it.second + it.third }
                .take(REPORT_TOP_N)
            val body = StringBuilder("<table><tr><th>插件</th><th>load</th><th>enable</th><th>合计</th><th>占启动</th></tr>")
            rows.forEach { (name, l, e) ->
                body.append("<tr><td class=\"frame\">").append(htmlEscape(name)).append("</td>")
                    .append(numCell(l)).append(numCell(e)).append(numCell(l + e))
                    .append(pctCell(l + e, totalMs)).append("</tr>")
            }
            body.append("</table>")
            return section("插件耗时 Top-N(load + enable,agent 实测)", body.toString())
        }
        // 无 agent:退回日志解析的 enable 近似
        val rows = profile.pluginTimings.sortedByDescending { it.enableMs }.take(REPORT_TOP_N)
        if (rows.isEmpty()) return section("插件耗时 Top-N", "<div class=\"empty\">无数据</div>")
        val body = StringBuilder("<table><tr><th>插件</th><th>启用间隔(近似)</th><th>占启动</th></tr>")
        rows.forEach { t ->
            body.append("<tr><td class=\"frame\">").append(htmlEscape(t.name)).append("</td>")
                .append(numCell(t.enableMs)).append(pctCell(t.enableMs, totalMs)).append("</tr>")
        }
        body.append("</table>")
        return section("插件耗时 Top-N(日志解析,启用间隔近似)", body.toString())
    }

    /**
     * 通用"名称→耗时"榜表;items 为 null/空时显示无数据。
     */
    private fun buildNamedTable(title: String, items: List<Pair<String, Long>>?, totalMs: Long): String {
        val list = (items ?: emptyList()).sortedByDescending { it.second }.take(REPORT_TOP_N)
        if (list.isEmpty()) return section(title, "<div class=\"empty\">无数据</div>")
        val body = StringBuilder("<table><tr><th>名称</th><th>耗时</th><th>占启动</th></tr>")
        list.forEach { (name, ms) ->
            body.append("<tr><td class=\"frame\">").append(htmlEscape(name)).append("</td>")
                .append(numCell(ms)).append(pctCell(ms, totalMs)).append("</tr>")
        }
        body.append("</table>")
        return section(title, body.toString())
    }

    /**
     * 采样热点榜:把所有线程的折叠栈按命中降序(滤除空闲等待栈),估算墙钟耗时(采样数 × 周期)。
     *
     * 这是暴露"非 hook 瓶颈"的关键视图:并行下载线程卡在 socket 读取数分钟,会以巨大的采样数浮到榜首,
     * 其调用栈含该插件(及其重定位后的 TabooLib)类名,据此即可定位"谁在下载、卡在哪"。
     */
    private fun buildSamplingHotspots(profile: StartupProfile, intervalMs: Long): String {
        val threads = profile.threadStacks ?: return ""
        val hots = ArrayList<Triple<String, List<String>, Long>>()
        threads.forEach { tp ->
            tp.stacks.forEach { st ->
                if (st.frames.isNotEmpty() && !isIdleLeaf(st.frames.last())) {
                    hots.add(Triple(tp.threadName, st.frames, st.sampleCount))
                }
            }
        }
        val titleSuffix = if (intervalMs > 0) "(估算耗时 = 采样数 × ${intervalMs}ms,已滤除空闲等待)" else "(已滤除空闲等待)"
        if (hots.isEmpty()) return section("采样热点 Top-N $titleSuffix", "<div class=\"empty\">无非空闲采样数据</div>")
        val rows = hots.sortedByDescending { it.third }.take(REPORT_TOP_N)
        val body = StringBuilder("<table><tr><th>线程</th><th>估算耗时</th><th>采样</th><th>热点调用栈(栈顶在前)</th></tr>")
        rows.forEach { (thread, frames, count) ->
            val stackTop = frames.asReversed().take(6).joinToString(" ← ") { shortFrame(it) }
            val est = if (intervalMs > 0) numCell(count * intervalMs) else "<td class=\"num\">-</td>"
            body.append("<tr><td class=\"frame\">").append(htmlEscape(thread)).append("</td>")
                .append(est).append("<td class=\"num\">").append(count).append("</td>")
                .append("<td class=\"frame\">").append(htmlEscape(stackTop)).append("</td></tr>")
        }
        body.append("</table>")
        return section("采样热点 Top-N $titleSuffix", body.toString())
    }

    /** 包装一个带标题的报告小节。 */
    private fun section(title: String, body: String): String = "<h2>${htmlEscape(title)}</h2>$body"

    /** 毫秒数值单元格。 */
    private fun numCell(ms: Long): String = "<td class=\"num\">${ms}ms</td>"

    /** 占比单元格(百分比 + 比例条)。 */
    private fun pctCell(ms: Long, totalMs: Long): String {
        val pct = (ms.toDouble() / totalMs * 100).coerceIn(0.0, 100.0)
        val barPx = (pct * 1.4).toInt()
        return "<td class=\"num\">${String.format(java.util.Locale.ROOT, "%.1f", pct)}% " +
            "<span class=\"bar\" style=\"width:${barPx}px\"></span></td>"
    }

    /** 判定栈顶帧是否为"空闲等待"(线程池 park / 选择器 wait / acceptor 等),这类不是启动瓶颈,报告中滤除。 */
    private fun isIdleLeaf(frame: String): Boolean = frame.substringAfterLast('#', "") in IDLE_LEAF_METHODS

    /** 帧标识简写:类简名#方法。 */
    private fun shortFrame(frame: String): String {
        val hash = frame.indexOf('#')
        if (hash < 0) return frame
        val cls = frame.substring(0, hash)
        val method = frame.substring(hash)
        val dot = cls.lastIndexOf('.')
        return (if (dot >= 0) cls.substring(dot + 1) else cls) + method
    }

    /** HTML 文本转义。 */
    private fun htmlEscape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** 报告各榜的展示条数。 */
    private const val REPORT_TOP_N = 15

    /** "空闲等待"的栈顶方法名集合(park/wait/选择器/acceptor 等),采样热点榜据此滤噪。 */
    private val IDLE_LEAF_METHODS = setOf(
        "park", "parkNanos", "wait", "epollWait", "poll0", "kevent0", "accept0", "accept"
    )
}

/**
 * 火焰图树构建用的可变节点(仅 [FlamegraphExporter] 内部使用)。
 *
 * 用 [LinkedHashMap] 保持子节点插入顺序(序列化时再按 value 降序),`child` 按帧名查找/新建实现折叠栈逐层并入。
 *
 * @property name 帧标识(`类全名#方法名`),根节点为 "root"。
 */
private class FrameNode(val name: String) {

    /** 经过本节点(本调用路径)的采样命中数。 */
    var value: Long = 0

    /** 子节点表:帧名 → 子节点。 */
    private val children = LinkedHashMap<String, FrameNode>()

    /** 取(无则建)指定帧名的子节点。 */
    fun child(frame: String): FrameNode = children.getOrPut(frame) { FrameNode(frame) }

    /** 序列化为火焰图节点 JSON;子节点按 value 降序(火焰图惯例:大块在左)。 */
    fun toJson(): String {
        val childJson = children.values
            .sortedByDescending { it.value }
            .joinToString(",") { it.toJson() }
        return "{\"name\":\"${escapeJson(name)}\",\"value\":$value,\"children\":[$childJson]}"
    }
}

/**
 * JSON 字符串转义(双引号、反斜杠、控制字符);供导出器与 [FrameNode] 公用,故置于文件级。
 */
private fun escapeJson(s: String): String =
    s.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
