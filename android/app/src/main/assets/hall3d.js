// 3D-движок «вид с места» (WebGL2). Поддерживает два формата зала:
//  • сетка кресел по параметрам (тестовый VIP-зал: origin/rowStep/seatStep);
//  • явные координаты кресел + инстансинг (Зал 1 IMAX: 24 × 54 = 1296 кресел одним вызовом).
function createHall3D(canvas, data, imgs, screen) {
  imgs = imgs || {};
  var gl = canvas.getContext('webgl2', { antialias: true, preserveDrawingBuffer: true, powerPreference: 'high-performance' });
  if (!gl) return null;
  function dec(s, T) { var bin = atob(s), u = new Uint8Array(bin.length); for (var i = 0; i < bin.length; i++) u[i] = bin.charCodeAt(i); return new T(u.buffer); }
  var MAXL = 48;
  var VS = '#version 300 es\nin vec3 aPos; in vec2 aUv; in vec3 aOff; uniform mat4 uVP; uniform mat4 uM; out vec3 vPos; out vec2 vUv;\nvoid main(){ vec3 p = (uM * vec4(aPos + aOff, 1.0)).xyz; vPos = p; vUv = aUv; gl_Position = uVP * vec4(p,1.0); }';
  var FS = '#version 300 es\nprecision highp float; in vec3 vPos; in vec2 vUv; uniform vec3 uColor; uniform int uMode; uniform sampler2D uTex; uniform vec3 uEye; uniform vec3 uScreen; uniform int uVip; uniform float uGain; uniform vec3 uL[' + MAXL + ']; uniform int uLN; out vec4 o;\n' +
    'void main(){\n' +
    ' if(uMode==7){ o = (vUv.y < 0.0 || vUv.y > 1.0) ? vec4(0.008,0.005,0.006,1.0) : vec4(texture(uTex,vUv).rgb,1.0); return; }\n' +
    ' if(uMode==8){ vec3 t = texture(uTex,vUv).rgb; o = vec4(pow(t,vec3(0.78))*1.16,1.0); return; }\n' +
    ' if(uMode==4 && uVip==3){ vec3 t = texture(uTex,vUv).rgb; o = vec4(pow(t,vec3(0.73))*1.44,1.0); return; }\n' +
    ' if(uMode==2 || uMode==4){ vec3 t = texture(uTex, vUv).rgb; o = vec4(t*1.12,1.0); return; }\n' +
    ' if(uMode==3){ vec3 t = texture(uTex, vUv).rgb; vec3 baked = pow(t*(uVip==2?0.66*uGain:(uVip==1?1.35:1.6*uGain)), vec3(0.85))+vec3(0.012,0.012,0.02); o = vec4(baked*(uVip==2?0.30:1.0),1.0); return; }\n' +
    ' if(uMode==1){ o = vec4(uColor,1.0); return; }\n' +
    ' if(uMode==6){ vec4 t6 = texture(uTex, vUv); if(t6.a < 0.5) discard; o = vec4(t6.rgb*0.9,1.0); return; }\n' +
    ' if(uMode==9){\n' +
    '   vec3 normal = normalize(cross(dFdx(vPos), dFdy(vPos)));\n' +
    '   float facingUp = smoothstep(0.25,0.95,abs(normal.y));\n' +
    '   vec3 upholsteryTone = mix(vec3(0.78,0.74,0.75),vec3(1.08,1.24,1.27),facingUp);\n' +
    '   o = vec4(uColor*upholsteryTone,1.0); return; }\n' +
    ' if(uVip==2 || uVip==3){ vec3 ambient = uColor*(uVip==2?0.057:0.085); ambient = ambient*1.25/(1.0+ambient*0.6); o = vec4(pow(ambient,vec3(0.9)),1.0); return; }\n' +
    ' if(uMode==5){ vec3 n5 = normalize(cross(dFdx(vPos), dFdy(vPos))); if(dot(n5, uEye - vPos) < 0.0) n5 = -n5;\n' +
    '   float l5 = 0.45 + 0.55*max(dot(n5, normalize(uEye + vec3(0.0,0.6,0.0) - vPos)),0.0);\n' +
    '   vec3 c5 = uColor*l5*0.95 + vec3(0.03,0.04,0.08); o = vec4(pow(c5,vec3(0.95)),1.0); return; }\n' +
    ' vec3 n = normalize(cross(dFdx(vPos), dFdy(vPos)));\n' +
    ' if(dot(n, uEye - vPos) < 0.0) n = -n;\n' +
    ' vec3 c = uColor*0.22;\n' +
    ' vec3 toS = uScreen - vPos; float dS = length(toS);\n' +
    ' c += vec3(0.24,0.28,0.48)*(0.25+0.75*max(dot(n,toS/dS),0.0))*(0.35+uColor)/(1.0+dS*dS*(uVip==1?0.012:0.0025))*0.6;\n' +
    ' for(int i=0;i<' + MAXL + ';i++){ if(i>=uLN) break; vec3 L = uL[i]-vPos; float dl = length(L);\n' +
    '   float lam = 0.35 + 0.65*max(dot(n,L/dl),0.0);\n' +
    '   c += vec3(0.10,0.22,0.95)*lam*(0.6+uColor)/(1.0+dl*dl*(uVip==1?0.16:0.05))*(uVip==1?0.42:0.30); }\n' +
    ' if(uVip==1){\n' +
    '  float stepY = clamp((vPos.x+7.5)/2.5+1.0,0.0,9.0)*0.2;\n' +
    '  float dz = abs(abs(vPos.z)-11.3); float sideG = vPos.x > -11.4 ? exp(-dz*dz*0.9) : 0.0;\n' +
    '  float dx = abs(vPos.x+11.1); float frontG = abs(vPos.z) < 11.6 ? exp(-dx*dx*0.9) : 0.0;\n' +
    '  float h = max(vPos.y - stepY, 0.0); float g = max(sideG, frontG)*exp(-h*0.55);\n' +
    '  c += vec3(0.12,0.95,0.10)*g*0.55; }\n' +
    ' float od = (uVip==0 && (vPos.x > 25.3 || abs(vPos.z) > 22.7)) ? 1.0 : 0.0;\n' +
    ' c += od*uColor*(0.4+0.5*max(dot(n, normalize(vec3(0.35,1.0,0.25))),0.0))*vec3(0.5,0.54,0.78);\n' +
    ' float d = length(vPos-uEye); c *= mix(1.0,0.8,clamp(d/60.0,0.0,1.0));\n' +
    ' c = c*1.25/(1.0+c*0.6);\n' +
    ' o = vec4(pow(c,vec3(0.9)),1.0); }';
  function sh(t, s) { var x = gl.createShader(t); gl.shaderSource(x, s); gl.compileShader(x); if (!gl.getShaderParameter(x, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(x)); return x; }
  var pr = gl.createProgram(); gl.attachShader(pr, sh(gl.VERTEX_SHADER, VS)); gl.attachShader(pr, sh(gl.FRAGMENT_SHADER, FS)); gl.linkProgram(pr);
  if (!gl.getProgramParameter(pr, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(pr));
  gl.useProgram(pr);
  var L = { pos: gl.getAttribLocation(pr, 'aPos'), uv: gl.getAttribLocation(pr, 'aUv'), off: gl.getAttribLocation(pr, 'aOff') };
  var U = {}; ['uVP', 'uColor', 'uMode', 'uTex', 'uEye', 'uLN', 'uL', 'uScreen', 'uVip', 'uGain', 'uM'].forEach(function (n) { U[n] = gl.getUniformLocation(pr, n); });

  var isVip2 = data.model === 'vip2';
  var isCompactImax = data.model === 'imax_compact';
  var isGrid = !data.seats;            // старый формат (VIP)
  var seatArr = data.seats ? dec(data.seats, Float32Array) : null;
  var instBuf = gl.createBuffer(), instN = 0;

  function mk(p, instanced, buf) {
    var vao = gl.createVertexArray(); gl.bindVertexArray(vao);
    var vb = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, vb); gl.bufferData(gl.ARRAY_BUFFER, p.posArr || dec(p.pos, Float32Array), gl.STATIC_DRAW);
    gl.enableVertexAttribArray(L.pos); gl.vertexAttribPointer(L.pos, 3, gl.FLOAT, false, 0, 0);
    if (p.uv || p.uvArr) { var ub = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, ub); gl.bufferData(gl.ARRAY_BUFFER, p.uvArr || dec(p.uv, Float32Array), gl.STATIC_DRAW); gl.enableVertexAttribArray(L.uv); gl.vertexAttribPointer(L.uv, 2, gl.FLOAT, false, 0, 0); }
    else gl.disableVertexAttribArray(L.uv);
    if (instanced) { gl.bindBuffer(gl.ARRAY_BUFFER, buf || instBuf); gl.enableVertexAttribArray(L.off); gl.vertexAttribPointer(L.off, 3, gl.FLOAT, false, 0, 0); gl.vertexAttribDivisor(L.off, 1); }
    else gl.disableVertexAttribArray(L.off);
    var ib = gl.createBuffer(); gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, ib); gl.bufferData(gl.ELEMENT_ARRAY_BUFFER, p.idxArr || dec(p.idx, Uint32Array), gl.STATIC_DRAW);
    gl.bindVertexArray(null);
    var mode = p.mode !== undefined ? p.mode : (p.tex ? 2 : (p.emissive ? 1 : 0));
    return { vao: vao, n: p.n, color: p.color, mode: mode, tex: p.tex, inst: instanced, gain: p.gain === undefined ? 1 : p.gain };
  }
  // Материалы мебели VIP. Шейдер высветляет сиденья и столешницы,
  // сохраняя более глубокий тон спинок и вертикальных поверхностей.
  var vipFurniture = {
    'Пуфик': [0.30, 0.17, 0.105],
    'Подстаканник': [0.51, 0.47, 0.40],
    'Чёрный': [0.27, 0.22, 0.21],
    'Стол': [0.46, 0.35, 0.28]
  };
  var parts = data.parts.map(function (p) {
    var mesh = mk(p, false);
    if (isVip2 && p.name === 'ПОЛ') mesh.gain *= 0.09;
    var furnitureColor = isVip2 && vipFurniture[p.name];
    if (furnitureColor) {
      mesh.mode = 9;
      mesh.color = furnitureColor;
    }
    return mesh;
  });
  var chair = data.chair.map(function (p) { return mk(p, !isGrid); });

  var texCache = {};
  function getTex(key) {
    if (texCache[key]) return texCache[key];
    var t = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, t);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, 1, 1, 0, gl.RGBA, gl.UNSIGNED_BYTE, new Uint8Array([200, 200, 210, 255]));
    var im = imgs[key];
    if (im) { gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, im); gl.generateMipmap(gl.TEXTURE_2D); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR_MIPMAP_LINEAR); }
    texCache[key] = t; return t;
  }
  // живой экран: текстура из холста, перезаливается, когда меняется содержимое
  var scrTex = gl.createTexture(), scrVer = -1;
  function screenTex() {
    gl.bindTexture(gl.TEXTURE_2D, scrTex);
    if (screen && screen.version() !== scrVer) {
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, screen.canvas);
      gl.generateMipmap(gl.TEXTURE_2D); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR_MIPMAP_LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
      scrVer = screen.version();
    }
    return scrTex;
  }

  var lights = data.lights || [], lightN = Math.min(MAXL, lights.length), lightArr = new Float32Array(MAXL * 3);
  for (var li = 0; li < lightN; li++) { lightArr[li * 3] = lights[li][0]; lightArr[li * 3 + 1] = lights[li][1]; lightArr[li * 3 + 2] = lights[li][2]; }
  var scr = data.screen || [-16.9, 5.1, 0];

  function seatPos(r, c) {
    if (isGrid) return [data.origin[0] + (r - 1) * data.rowStep[0], data.origin[1] + (r - 1) * data.rowStep[1], data.origin[2] + (c - 1) * data.seatStep];
    var i = ((r - 1) * data.cols + (c - 1)) * 3; return [seatArr[i], seatArr[i + 1], seatArr[i + 2]];
  }
  function centerPos() {   // середина зала: средний ряд, по центру между блоками
    var a = seatPos(1, 1), b = seatPos(data.rows, data.cols);
    return [(a[0] + b[0]) / 2, (a[1] + b[1]) / 2, (a[2] + b[2]) / 2];
  }
  var lastHidden = null;
  function fillInstances(row, col) {   // все кресла, кроме того, в котором «сидим»
    var key = row + ':' + col; if (key === lastHidden) return; lastHidden = key;
    var a = new Float32Array(data.rows * data.cols * 3), k = 0;
    for (var r = 1; r <= data.rows; r++) for (var c = 1; c <= data.cols; c++) {
      if (r === row && c === col) continue;
      var i = ((r - 1) * data.cols + (c - 1)) * 3; a[k++] = seatArr[i]; a[k++] = seatArr[i + 1]; a[k++] = seatArr[i + 2];
    }
    instN = k / 3; gl.bindBuffer(gl.ARRAY_BUFFER, instBuf); gl.bufferData(gl.ARRAY_BUFFER, a, gl.DYNAMIC_DRAW);
  }
  function persp(fovy, a, n, f) { var t = 1 / Math.tan(fovy / 2); return [t / a, 0, 0, 0, 0, t, 0, 0, 0, 0, (f + n) / (n - f), -1, 0, 0, 2 * f * n / (n - f), 0]; }
  function look(e, c, u) {
    var z = [e[0] - c[0], e[1] - c[1], e[2] - c[2]], zl = Math.hypot(z[0], z[1], z[2]); z = z.map(function (v) { return v / zl; });
    var x = [u[1] * z[2] - u[2] * z[1], u[2] * z[0] - u[0] * z[2], u[0] * z[1] - u[1] * z[0]], xl = Math.hypot(x[0], x[1], x[2]); x = x.map(function (v) { return v / xl; });
    var y = [z[1] * x[2] - z[2] * x[1], z[2] * x[0] - z[0] * x[2], z[0] * x[1] - z[1] * x[0]];
    return [x[0], y[0], z[0], 0, x[1], y[1], z[1], 0, x[2], y[2], z[2], 0, -(x[0] * e[0] + x[1] * e[1] + x[2] * e[2]), -(y[0] * e[0] + y[1] * e[1] + y[2] * e[2]), -(z[0] * e[0] + z[1] * e[1] + z[2] * e[2]), 1];
  }
  function mul(a, b) { var o = new Array(16); for (var i = 0; i < 4; i++) for (var j = 0; j < 4; j++) { var s = 0; for (var k = 0; k < 4; k++) s += a[k * 4 + j] * b[i * 4 + k]; o[i * 4 + j] = s; } return o; }

  function render(row, col, yaw, pitch) {
    yaw = yaw || 0; pitch = pitch || 0;
    var overview = !row;   // место не выбрано — камера в центре зала, все кресла на месте
    if (!overview) { row = Math.max(1, Math.min(data.rows, row)); col = Math.max(1, Math.min(data.cols, col)); }
    var s = overview ? centerPos() : seatPos(row, col), eye, t0;
    if (overview) {
      eye = [s[0], s[1] + 1.7, 0];
      t0 = [scr[0] - eye[0], scr[1] - (isVip2 ? 0.20 : 1.5) - eye[1], -eye[2]];
      if (!isGrid) fillInstances(0, 0);
    } else if (isGrid) { eye = [s[0] + 0.15, s[1] + 1.2, s[2]]; t0 = [-17.0 - eye[0], 5.0 - eye[1], s[2] * 0.35 - eye[2]]; }
    else {
      eye = [s[0] + (data.eyeBack || 0.1), s[1] + (data.eyeUp || 0.8), s[2]];
      t0 = [scr[0] - eye[0], scr[1] - (isVip2 ? 0.25 : 0.8) - eye[1], (isVip2 ? scr[2] : s[2] * 0.3) - eye[2]];
      fillInstances(row, col);
    }
    var baseYaw = Math.atan2(-t0[2], -t0[0]);
    var basePitch = Math.atan2(t0[1], Math.hypot(t0[0], t0[2]));
    var a = baseYaw + yaw, b = Math.max(-1.2, Math.min(1.2, basePitch + pitch));
    var target = [eye[0] - Math.cos(b) * Math.cos(a), eye[1] + Math.sin(b), eye[2] - Math.cos(b) * Math.sin(a)];
    return scene(eye, target, isVip2 ? 73 : (isGrid ? 58 : 62), isGrid && !overview ? [row, col] : null);
  }

  var ID4 = new Float32Array([1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]);
  function scene(eye, target, fov, hide) {
    var w = canvas.width, h = canvas.height;
    gl.viewport(0, 0, w, h); gl.clearColor(0.03, 0.03, 0.045, 1); gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT);
    gl.enable(gl.DEPTH_TEST); gl.disable(gl.CULL_FACE);
    var aspect = w / h;
    var verticalFov = fov * Math.PI / 180;
    if (aspect < 1) verticalFov = 2 * Math.atan(Math.tan(verticalFov / 2) / aspect);
    var vp = mul(persp(verticalFov, aspect, 0.1, 120), look(eye, target, [0, 1, 0]));
    gl.uniformMatrix4fv(U.uVP, false, new Float32Array(vp)); gl.uniform3fv(U.uEye, eye);
    gl.uniformMatrix4fv(U.uM, false, ID4);
    gl.uniform3fv(U.uScreen, scr); gl.uniform1i(U.uVip, isCompactImax ? 3 : (isVip2 ? 2 : (isGrid ? 1 : 0)));
    gl.activeTexture(gl.TEXTURE0); gl.uniform1i(U.uTex, 0); gl.uniform3fv(U.uL, lightArr); gl.uniform1i(U.uLN, (isCompactImax || isVip2) ? 0 : lightN);
    function draw(m, off) {
      if (m.mode === 4 || m.mode === 7) screenTex(); else if (m.tex) gl.bindTexture(gl.TEXTURE_2D, getTex(m.tex));
      gl.bindVertexArray(m.vao);
      if (!m.inst) gl.vertexAttrib3f(L.off, off[0], off[1], off[2]);
      gl.vertexAttrib2f(L.uv, 0, 0);
      gl.uniform3fv(U.uColor, m.color); gl.uniform1i(U.uMode, m.mode); gl.uniform1f(U.uGain, m.gain);
      if (m.inst) { if (instN > 0) gl.drawElementsInstanced(gl.TRIANGLES, m.n, gl.UNSIGNED_INT, 0, instN); }
      else gl.drawElements(gl.TRIANGLES, m.n, gl.UNSIGNED_INT, 0);
    }
    parts.forEach(function (m) { draw(m, [0, 0, 0]); });
    if (isGrid) {
      for (var r = 1; r <= data.rows; r++) for (var c = 1; c <= data.cols; c++) {
        if (hide && r === hide[0] && c === hide[1]) continue;
        var p = seatPos(r, c); chair.forEach(function (m) { draw(m, p); });
      }
    } else chair.forEach(function (m) { draw(m, null); });
    gl.bindVertexArray(null);
    return vp;
  }

  return { render: render };
}
