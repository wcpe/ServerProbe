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
        val totalMs = profile.totalMs
        val serverId = escapeJson(profile.serverId)
        val mcVersion = escapeJson(profile.mcVersion)
        val threadCount = profile.threadStacks?.size ?: 0
        val totalSamples = profile.threadStacks.orEmpty().sumOf { tp -> tp.stacks.sumOf { it.sampleCount } }
        val eventCount = profile.timelineEvents?.size ?: 0

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
<div class="tab active" onclick="switchTab('flamegraph')">火焰图</div>
<div class="tab" onclick="switchTab('waterfall')">时间线</div>
</div>

<div id="flamegraph-panel" class="panel active">
<div class="toolbar">
<label for="thread-select">线程:</label>
<select id="thread-select"></select>
<input type="text" id="search" placeholder="搜索帧名(如 org.bukkit, onEnable)..." autocomplete="off">
<div class="info">左键缩放 | 右键返回上层 | 悬浮查看详情</div>
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
function switchTab(name) {
document.querySelectorAll('.tab').forEach(function(t, i) {
t.classList.toggle('active', (i === 0 && name === 'flamegraph') || (i === 1 && name === 'waterfall'));
});
document.getElementById('flamegraph-panel').classList.toggle('active', name === 'flamegraph');
document.getElementById('waterfall-panel').classList.toggle('active', name === 'waterfall');
if (name === 'waterfall') {
waterfallRenderer.resize();
waterfallRenderer.draw();
} else if (flamegraphRenderer) {
flamegraphRenderer.resize();
flamegraphRenderer.draw();
}
}

// 每个线程一棵火焰图树:[{name, root:{name,value,children}}, ...]
var FLAMEGRAPH_THREADS = $flamegraphThreads;
var WATERFALL_DATA = $waterfallData;

function escapeHtml(s) {
var d = document.createElement('div');
d.appendChild(document.createTextNode(s));
return d.innerHTML;
}

// === 火焰图渲染器(Canvas,多层 + 多线程) ===
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
this._bind();
this.resize();
this.draw();
this._updateBreadcrumb();
}

R.prototype._bind = function() {
var self = this;
this.canvas.addEventListener('mousemove', function(e) { self._onMouseMove(e); });
this.canvas.addEventListener('mouseleave', function() { self._hideTooltip(); });
this.canvas.addEventListener('click', function(e) { self._onClick(e); });
this.canvas.addEventListener('contextmenu', function(e) { self._onRightClick(e); });
window.addEventListener('resize', function() { self.resize(); self.draw(); });
};

R.prototype.setThread = function(idx) {
this.threadIdx = idx;
this.root = this.threads[idx].root;
this.currentRoot = this.root;
this.zoomStack = [this.root];
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

// 自当前缩放根递归收集可见节点(宽度=占 currentRoot 的比例 * 画布宽);水平依次排布同层兄弟。
R.prototype._flattenVisible = function(node, depth, x, arr) {
var rootVal = this.currentRoot.value || 1;
var w = this.logicalW * (node.value / rootVal);
if (w < 0.4) return;
arr.push({ node: node, depth: depth, x: x, w: w });
var children = node.children || [];
var childX = x;
for (var i = 0; i < children.length; i++) {
var cw = this.logicalW * (children[i].value / rootVal);
this._flattenVisible(children[i], depth + 1, childX, arr);
childX += cw;
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
var x = item.x;
var y = this.logicalH - (item.depth + 1) * (this.barHeight + this.padding) - 20;
var w = item.w;
if (w < 1) continue;
ctx.fillStyle = this._color(item.node.name);
ctx.fillRect(x, y, w - 1, this.barHeight);
if (w > 32) {
ctx.fillStyle = '#1e1e2e';
ctx.font = '11px "Cascadia Code","Fira Code",monospace';
ctx.save();
ctx.beginPath();
ctx.rect(x, y, w - 1, this.barHeight);
ctx.clip();
ctx.fillText(this._shortName(item.node.name), x + 4, y + 14);
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
var y = this.logicalH - (item.depth + 1) * (this.barHeight + this.padding) - 20;
if (mx >= item.x && mx <= item.x + item.w && my >= y && my <= y + this.barHeight) {
return item;
}
}
return null;
};

R.prototype._showTooltip = function(item, e) {
var tip = document.getElementById('tooltip');
var rootVal = this.currentRoot.value || 1;
var pct = (item.node.value / rootVal * 100).toFixed(2);
tip.querySelector('.tt-name').textContent = item.node.name;
tip.querySelector('.tt-detail').textContent = item.node.value + ' samples (' + pct + '% of view) | depth=' + item.depth;
tip.style.display = 'block';
tip.style.left = (e.clientX + 12) + 'px';
tip.style.top = (e.clientY + 12) + 'px';
};

R.prototype._hideTooltip = function() {
document.getElementById('tooltip').style.display = 'none';
};

R.prototype._onMouseMove = function(e) {
var hit = this._hitTest(e);
if (hit) {
this._showTooltip(hit, e);
this.canvas.style.cursor = 'pointer';
} else {
this._hideTooltip();
this.canvas.style.cursor = 'default';
}
};

R.prototype._onClick = function(e) {
var hit = this._hitTest(e);
if (hit && hit.node !== this.currentRoot && (hit.node.children || []).length > 0) {
this.currentRoot = hit.node;
this.zoomStack.push(hit.node);
this.resize();
this.draw();
this._updateBreadcrumb();
this._hideTooltip();
}
};

R.prototype._onRightClick = function(e) {
e.preventDefault();
if (this.zoomStack.length > 1) {
this.zoomStack.pop();
this.currentRoot = this.zoomStack[this.zoomStack.length - 1];
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
opt.textContent = FLAMEGRAPH_THREADS[i].name + ' (' + total + ')';
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
} else {
document.getElementById('flamegraph-empty').style.display = 'block';
document.getElementById('thread-select').style.display = 'none';
document.getElementById('search').style.display = 'none';
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
        return threads.joinToString(",", "[", "]") { tp -> threadTreeJson(tp) }
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
