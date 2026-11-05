const estado = document.getElementById('estado');
function setEstado(t, err) { estado.textContent = t; estado.className = 'estado' + (err ? ' err' : ''); }
// Mensaje para el usuario: el texto que manda el servidor/driver, o `porDefecto` si fallo la red
// (fetch lanza TypeError "Failed to fetch", en ingles) o el error no trae texto.
function msgError(e, porDefecto) { return e instanceof TypeError || !(e && e.message) ? porDefecto : e.message; }

// Indicador de conexion con el servidor (mismo WiFi + PC encendido).
const conn = document.getElementById('conn');
let enLinea = null;
async function revisarConexion() {
  let ok = false;
  try { ok = (await fetch('/ping', { cache: 'no-store' })).ok; } catch {}
  if (ok !== enLinea) {
    enLinea = ok;
    conn.textContent = ok ? '● Conectado' : '● Sin conexión con el servidor (si usas el PC: mismo WiFi y PC encendido)';
    conn.className = 'conn ' + (ok ? 'ok' : 'mal');
    if (ok) cargarDispositivos();
  }
}
// En la app la "API" corre en el propio celular: no hay servidor que se pueda caer, basta comprobarlo al volver.
revisarConexion(); if (!window.App) setInterval(revisarConexion, 4000);
document.addEventListener('visibilitychange', () => { if (!document.hidden) revisarConexion(); });

// Dispositivos: cada uno declara sus capacidades (keys, text, apps, volume).
const selDev = document.getElementById('dev');
let dispositivos = [], actual = null;
const CAPS = ['keys', 'text', 'apps', 'volume', 'pair', 'voice'];
function aplicarCaps() {
  const caps = actual ? actual.caps : [];
  CAPS.forEach(c => document.body.classList.toggle('c-' + c, caps.includes(c)));
  // Cada marca tiene su propio mando (clases m-roku, m-android-tv, m-hisense...): ver estilos.css
  [...document.body.classList].filter(c => c.startsWith('m-')).forEach(c => document.body.classList.remove(c));
  if (actual) document.body.classList.add('m-' + actual.marca.toLowerCase().replace(/[^a-z0-9]+/g, '-'));
  // Si el dispositivo declara qué teclas admite, se ocultan los botones de las demás.
  const teclas = actual && actual.teclas;
  document.querySelectorAll('[data-k]').forEach(b => b.classList.toggle('sin-tecla', !!teclas && !teclas.includes(b.dataset.k)));
  // El panel de ajustes/canales/numeros solo existe en dispositivos que declaran esas teclas (hoy: Android TV)
  document.getElementById('detMas').style.display = teclas && teclas.includes('Num0') ? '' : 'none';
  const fu = (actual && actual.fuentes) || [];
  document.getElementById('detFuente').style.display = fu.length ? '' : 'none';
  const cont = document.getElementById('fuentes'); cont.textContent = '';
  const ETQ = { HDMI1: 'HDMI 1', HDMI2: 'HDMI 2', HDMI3: 'HDMI 3', Composite: 'Compuesto', TV: 'TV', Inicio: 'Inicio' };
  fu.forEach(n => { const b = document.createElement('button'); b.textContent = ETQ[n] || n; b.onclick = () => fuente(n); cont.appendChild(b); });
  appsCargadas = false; document.getElementById('apps').textContent = '';
  document.getElementById('detApps').open = !!actual && actual.marca === 'Roku';  // en el Roku las apps son parte del mando
  document.getElementById('volnum').textContent = '';
  refrescarAhora();
  if (caps.includes('volume')) refrescarVolumen();
  if (window.App) App.volumen(caps.includes('volume'));  // la app usa los botones de volumen del celular
  revisarCompartido();
  mostrarMac();
}
// MAC para Wake-on-LAN (solo app): Android no deja leerla de la red, se escribe una vez.
const macTv = document.getElementById('macTv');
function mostrarMac() {
  const ok = !!(window.App && App.mac && actual && actual.caps.includes('keys') && actual.marca === 'Android TV');
  document.getElementById('bloqueMac').style.display = ok ? '' : 'none';
  if (ok) macTv.value = App.mac(actual.id);
}
document.getElementById('macGuardar').addEventListener('click', () => {
  if (!actual) { setEstado('Elige un dispositivo', true); return; }
  if (App.guardarMac(actual.id, macTv.value)) { macTv.value = App.mac(actual.id); setEstado('✓ MAC guardada: Encender también la despierta por la red'); }
  else setEstado('La MAC son 6 pares de letras y números, como aa:bb:cc:dd:ee:ff', true);
});
// "Ahora": qué muestra el Roku (inicio, salvapantallas o la app y su estado). Solo para dispositivos que lo informan.
const ahora = document.getElementById('ahora');
const REPRODUCTOR = { play: 'reproduciendo', pause: 'en pausa', buffer: 'cargando', startup: 'cargando' };
async function refrescarAhora() {
  if (!actual || actual.marca !== 'Roku' || document.hidden) { ahora.textContent = ''; return; }
  const id = actual.id;
  try {
    const e = await (await fetch('/d/' + id + '/estado', { cache: 'no-store' })).json();
    if (!actual || actual.id !== id) return;  // se cambió de dispositivo mientras tanto
    ahora.textContent = e.actividad === 'salvapantallas' ? 'Salvapantallas' : e.actividad === 'inicio' ? 'En el inicio'
      : (e.app || '') + (REPRODUCTOR[e.reproductor] ? ' · ' + REPRODUCTOR[e.reproductor] : '');
  } catch { ahora.textContent = ''; }
}
setInterval(refrescarAhora, 5000);
document.addEventListener('visibilitychange', refrescarAhora);
// ● en línea, ○ sin respuesta, nada mientras no se sabe (lo actualiza refrescarEnLinea)
const enLineaDev = {};
function etiqueta(d) {
  const p = enLineaDev[d.id] === true ? '● ' : enLineaDev[d.id] === false ? '○ ' : '';
  return p + d.nombre + (d.modelo && !d.nombre.includes(d.modelo) ? ' · ' + d.modelo : '');
}
async function refrescarEnLinea() {
  if (document.hidden || !dispositivos.length) return;
  await Promise.all(dispositivos.map(async d => {
    try { enLineaDev[d.id] = (await (await fetch('/d/' + d.id + '/online', { cache: 'no-store' })).json()).online; }
    catch { enLineaDev[d.id] = false; }
  }));
  [...selDev.options].forEach(o => { const d = dispositivos.find(x => x.id === o.value); if (d) o.textContent = etiqueta(d); });
}
setInterval(refrescarEnLinea, 10000);
document.addEventListener('visibilitychange', refrescarEnLinea);

async function cargarDispositivos() {
  let escaneando = false;
  try {
    const r = await fetch('/devices', { cache: 'no-store' });
    escaneando = r.headers.get('X-Escaneando') === '1';  // la app muestra la lista guardada mientras reescanea
    dispositivos = await r.json();
  } catch { return; }
  if (escaneando) {
    fetch('/scan?esperar=1', { method: 'POST' }).then(() => cargarDispositivos()).catch(() => {});
    if (!dispositivos.length) { setEstado('Buscando dispositivos...'); return; }
  }
  const guardado = localStorage.getItem('dispositivo');
  selDev.textContent = '';
  dispositivos.forEach(d => {
    const o = document.createElement('option');
    o.value = d.id; o.textContent = etiqueta(d);
    selDev.appendChild(o);
  });
  if (!dispositivos.length) { setEstado('Sin dispositivos: enciéndelos y pulsa Buscar', true); actual = null; aplicarCaps(); return; }
  selDev.value = dispositivos.some(d => d.id === guardado) ? guardado : dispositivos[0].id;
  // Si el elegido no cambio (p. ej. al terminar el escaneo de fondo), no se rehace la pantalla: no cierra paneles abiertos
  const nuevo = dispositivos.find(d => d.id === selDev.value);
  if (actual && nuevo && JSON.stringify(actual) === JSON.stringify(nuevo)) actual = nuevo; else elegir();
  refrescarEnLinea();
}
function elegir() {
  actual = dispositivos.find(d => d.id === selDev.value) || null;
  if (actual) { localStorage.setItem('dispositivo', actual.id); if (window.App && App.elegido) App.elegido(actual.id); }  // el widget usa esa TV
  aplicarCaps();
}
selDev.addEventListener('change', elegir);
document.getElementById('buscar').addEventListener('click', async () => {
  setEstado('Buscando dispositivos...');
  try { const r = await (await fetch('/scan', { method: 'POST' })).json(); await cargarDispositivos(); setEstado('✓ ' + dispositivos.length + ' dispositivo(s)' + (r.red ? ' · red ' + r.red : '')); }
  catch { setEstado('Error al buscar', true); }
});

async function refrescarVolumen() {
  if (!actual || !actual.caps.includes('volume')) return;
  try {
    const e = await (await fetch('/d/' + actual.id + '/estado')).json();
    document.getElementById('volnum').textContent = e.muted ? 'Silenciado' : e.volume === undefined ? '' : 'Volumen: ' + e.volume + (e.max ? ' / ' + e.max : '');
  } catch {}
}

// Respuesta háptica al tocar, para que los botones se sientan "físicos". En la app usa el vibrador nativo
// (clic nítido); en el navegador cae a navigator.vibrate. Tipos: click (al pulsar), tick (repetir/soltar), fuerte (mantener).
function tocar(tipo) {
  try {
    if (window.App && App.tocar) App.tocar(tipo);
    else if (navigator.vibrate) navigator.vibrate(tipo === 'fuerte' ? 25 : tipo === 'tick' ? 6 : 12);
  } catch {}
}
document.addEventListener('pointerdown', e => { if (e.target.closest('button, summary, .app, select')) tocar('click'); }, true);
document.addEventListener('pointerup', e => { if (e.target.closest('[data-k]')) tocar('tick'); }, true);

const NOMBRE_TECLA = { Up: 'Arriba', Down: 'Abajo', Left: 'Izquierda', Right: 'Derecha', Select: 'OK', Back: 'Atrás', Home: 'Inicio', VolumeUp: 'Volumen +', VolumeDown: 'Volumen −', VolumeMute: 'Silencio', Play: 'Reproducir/Pausa', Rev: 'Retroceder', Fwd: 'Avanzar', Info: 'Info', Stop: 'Detener', Red: 'Rojo', Green: 'Verde', Yellow: 'Amarillo', Blue: 'Azul', PowerOff: 'Apagar', Power: 'Encender' };
if (window.App && App.vozIniciar) document.body.classList.add('en-app');
function vozEstado(t, err) { setEstado(t, err); }
// Actualizaciones (solo en la app): GitHub Releases
function actualizacionEstado(t, err) { setEstado(t, err); const e = document.getElementById('actEstado'); e.textContent = t; e.style.color = err ? '#ff8a80' : ''; }
function actualizacionInfo(txt, err) {
  const b = document.getElementById('instalarAct'), n = document.getElementById('actNotas');
  if (err) { setEstado(txt, true); return; }
  const r = JSON.parse(txt);
  document.getElementById('verActual').textContent = r.actual;
  b.style.display = r.hay ? '' : 'none';
  n.style.display = r.hay ? '' : 'none';
  if (r.hay) {
    b.textContent = 'Actualizar a ' + r.ultima;
    n.textContent = 'Novedades de ' + r.ultima + ':\n' + r.notas.replace(/\*\*/g, '').replace(/^#+\s*/gm, '').replace(/`/g, '');
    setEstado('Hay una versión nueva: ' + r.ultima + ' (en Ajustes)');
    document.getElementById('detAjustes').open = true;
  } else setEstado('✓ Ya tienes la última versión (' + r.actual + ')');
}
if (window.App && App.buscarActualizacion) {
  document.getElementById('verActual').textContent = App.version();
  document.getElementById('buscarAct').addEventListener('click', () => { setEstado('Buscando actualizaciones...'); App.buscarActualizacion(false); });
  document.getElementById('instalarAct').addEventListener('click', () => App.actualizar());
  setTimeout(() => App.buscarActualizacion(true), 1500);  // al abrir: avisa solo si hay una nueva
}
(function () {
  const b = document.getElementById('voz');
  let activo = false;
  b.addEventListener('pointerdown', e => { if (!actual || activo) return; activo = true; b.setPointerCapture(e.pointerId); App.vozIniciar(actual.id); });
  const soltar = () => { if (!activo) return; activo = false; App.vozFin(); };
  b.addEventListener('pointerup', soltar); b.addEventListener('pointercancel', soltar);
  b.addEventListener('contextmenu', e => e.preventDefault());
})();
async function key(k) {
  if (!actual) { setEstado('Elige un dispositivo', true); return; }
  try {
    const r = await fetch('/d/' + actual.id + '/key/' + k, { method: 'POST' });
    if (!r.ok) throw new Error(await r.text());
    setEstado('✓ ' + (NOMBRE_TECLA[k] || k));
    if (k.startsWith('Volume')) setTimeout(refrescarVolumen, 250);  // la TV avisa el nuevo nivel un instante despues
  } catch (e) { setEstado(msgError(e, 'Sin conexión con ' + actual.nombre), true); }
}

// Botones de volumen del celular (los llama la app). Al mantenerlos, Android repite la tecla cada ~50 ms:
// como en los botones de la web, no se manda otra hasta que vuelva la anterior, y a lo sumo una cada 150 ms.
let fisicaEnCurso = false, ultimaFisica = 0;
function teclaFisica(k) {
  if (fisicaEnCurso || Date.now() - ultimaFisica < 150) return;
  fisicaEnCurso = true; ultimaFisica = Date.now();
  key(k).finally(() => { fisicaEnCurso = false; });
}

async function fuente(n) {
  if (!actual) { setEstado('Elige un dispositivo', true); return; }
  try {
    const r = await fetch('/d/' + actual.id + '/fuente/' + encodeURIComponent(n), { method: 'POST' });
    if (!r.ok) throw new Error(await r.text());
    setEstado('✓ ' + n);
  } catch (e) { setEstado(msgError(e, 'Sin conexión con ' + actual.nombre), true); }
}

// Pulsación larga real (dispositivos con `largo`: Android TV y Roku): al pasar 450 ms se manda "start" y al soltar "end",
// y la propia TV hace el desplazamiento continuo. OK distingue toque (corta, al soltar) de mantener (larga).
// El driver suelta solo a los 5 s si se pierde el "end".
let holdActivo = null;  // función que suelta la tecla mantenida ahora
function mantener(k, accion) {
  return fetch('/d/' + actual.id + '/key/' + k + '?accion=' + accion, { method: 'POST', keepalive: true })
    .then(async r => { if (!r.ok) throw new Error(await r.text()); })
    .catch(e => setEstado(msgError(e, 'Error al mantener la tecla'), true));
}
['visibilitychange', 'blur'].forEach(ev => window.addEventListener(ev, () => { if (holdActivo) holdActivo(); }));

document.querySelectorAll('[data-k]').forEach(b => {
  const k = b.dataset.k, rep = b.hasAttribute('data-rep');
  let t1, t2, enCurso = false, activo = false, mantenido = false, diferido = false, inicio = null;
  // Al mantener pulsado no se manda otra tecla hasta que vuelva la anterior: si la TV va lenta no se acumulan
  // y deja de moverse al soltar.
  const pulsar = () => { if (enCurso) return; enCurso = true; key(k).finally(() => { enCurso = false; }); };
  // `largo` del dispositivo: true = flechas (rep) y OK; o la lista de teclas que se pueden mantener (Roku: sin OK)
  const largo = () => { const l = actual && actual.largo; return l === true ? (rep || k === 'Select') : Array.isArray(l) && l.includes(k); };
  const soltar = () => {
    if (!activo) return;
    activo = false; clearTimeout(t1); clearInterval(t2); b.classList.remove('on');
    if (holdActivo === soltar) holdActivo = null;
    if (mantenido) { mantenido = false; inicio.then(() => mantener(k, 'end')); }  // "end" siempre despues de "start"
    else if (diferido) pulsar();                                                   // toque corto de OK
  };
  b.addEventListener('pointerdown', e => {
    e.preventDefault(); b.classList.add('on'); activo = true; mantenido = false;
    diferido = largo() && k === 'Select';
    if (largo()) {
      if (!diferido) pulsar();  // las flechas responden al instante y luego pasan a mantenerse
      t1 = setTimeout(() => {
        mantenido = true; holdActivo = soltar;
        tocar('fuerte');
        inicio = mantener(k, 'start');
      }, 450);
      return;
    }
    pulsar();
    if (rep) t1 = setTimeout(() => { t2 = setInterval(() => { tocar('tick'); pulsar(); }, 180); }, 450);
  });
  ['pointerup', 'pointerleave', 'pointercancel'].forEach(ev => b.addEventListener(ev, soltar));
  b.addEventListener('contextmenu', e => e.preventDefault());
});

const inp = document.getElementById('texto');
async function enviarTexto() {
  const t = inp.value; if (!t) return;
  if (!actual) { setEstado('Elige un dispositivo', true); return; }
  setEstado('Enviando texto...');
  try {
    const r = await fetch('/d/' + actual.id + '/text?t=' + encodeURIComponent(t), { method: 'POST' });
    if (!r.ok) throw new Error(await r.text());
    inp.value = '';
    const aviso = r.status === 200 ? await r.text() : '';  // 200 = se escribió, pero se omitieron caracteres
    setEstado(aviso ? '✓ Texto enviado. ' + aviso : '✓ Texto enviado', !!aviso);
  } catch (e) { setEstado(msgError(e, 'Error al enviar texto'), true); }
}
// Roku: busca el título en todas sus apps (abre los resultados en la TV; no reproduce nada)
document.getElementById('buscarTitulo').addEventListener('click', async () => {
  const t = inp.value.trim(); if (!t) { setEstado('Escribe qué buscar', true); return; }
  if (!actual) { setEstado('Elige un dispositivo', true); return; }
  setEstado('Buscando en la TV...');
  try {
    const r = await fetch('/d/' + actual.id + '/search?q=' + encodeURIComponent(t), { method: 'POST' });
    if (!r.ok) throw new Error(await r.text());
    setEstado('✓ Resultados en la TV');
  } catch (e) { setEstado(msgError(e, 'No se pudo buscar'), true); }
});
document.getElementById('enviar').addEventListener('click', enviarTexto);
inp.addEventListener('keydown', e => { if (e.key === 'Enter') enviarTexto(); });

// Enlace recibido por "Compartir" en la app: solo se envia cuando el usuario pulsa el boton.
function revisarCompartido() {
  const url = window.App && App.compartido ? App.compartido() : '';
  document.getElementById('compartir').style.display = url ? 'block' : 'none';
  if (!url) return;
  document.getElementById('compUrl').textContent = url;
  const ok = !!actual && actual.caps.includes('link');
  const b = document.getElementById('compEnviar');
  b.disabled = !ok;
  b.textContent = ok ? 'Enviar a ' + actual.nombre : 'Elige una Android TV';
}
document.getElementById('compEnviar').addEventListener('click', async () => {
  const url = App.compartido();
  if (!url || !actual) return;
  try {
    const r = await fetch('/d/' + actual.id + '/open?u=' + encodeURIComponent(url), { method: 'POST' });
    if (!r.ok) throw new Error(await r.text());
    App.limpiarCompartido(); revisarCompartido(); setEstado('✓ Enviado a ' + actual.nombre + '. Si la TV muestra el salvapantallas, pulsa Atrás');  // con el salvapantallas la app se abre detrás
  } catch (e) { setEstado(msgError(e, 'No se pudo enviar'), true); }
});
document.getElementById('compCancel').addEventListener('click', () => { App.limpiarCompartido(); revisarCompartido(); });

async function pairar(arg, ok) {
  if (!actual) { setEstado('Elige un dispositivo', true); return false; }
  try {
    const r = await fetch('/d/' + actual.id + '/pair/' + arg, { method: 'POST' });
    if (!r.ok) throw new Error(await r.text());
    setEstado(ok); return true;
  } catch (e) { setEstado(msgError(e, 'Sin conexión con ' + actual.nombre), true); return false; }
}
const pairCod = document.getElementById('pairCod');
document.getElementById('pairIni').addEventListener('click', async () => {
  if (await pairar('start', 'Mira ' + (actual ? actual.nombre : 'la TV') + ' y escribe el código que muestra')) { pairCod.value = ''; pairCod.focus(); }
});
async function enviarCodigo() {
  const c = pairCod.value.trim();
  if (c.length === 6 && await pairar(encodeURIComponent(c), '✓ Emparejada')) { pairCod.value = ''; await cargarDispositivos(); }
}
document.getElementById('pairOk').addEventListener('click', enviarCodigo);
// Solo 0-9 A-F en mayúsculas; al escribir el 6.º carácter se empareja sola.
pairCod.addEventListener('input', () => {
  pairCod.value = pairCod.value.toUpperCase().replace(/[^0-9A-F]/g, '');
  if (pairCod.value.length === 6) enviarCodigo();
});

// Logos simplificados (formas propias) de las apps que no traen icono del dispositivo, por nombre en minúsculas.
// Son constantes de esta página: por eso es seguro ponerlos con innerHTML.
const SVG = (cuerpo) => '<svg width="38" height="38" viewBox="0 0 24 24" fill="#fff" aria-hidden="true">' + cuerpo + '</svg>';
const LOGOS = {
  'youtube': SVG('<path fill-rule="evenodd" d="M6 5h12a4 4 0 0 1 4 4v6a4 4 0 0 1-4 4H6a4 4 0 0 1-4-4V9a4 4 0 0 1 4-4zm4 4v6l5.2-3z"/>'),
  'netflix': SVG('<path d="M6.5 3h3.1l5 10.2V3h3v18h-3L9.5 10.8V21h-3z"/>'),
  'prime video': SVG('<text x="12" y="11" text-anchor="middle" font-size="7.8" font-weight="700" font-family="sans-serif">prime</text>' +
    '<path d="M4 15.5c5 3.2 11 3.2 16 0" stroke="#fff" stroke-width="1.8" fill="none" stroke-linecap="round"/><path d="M17.6 13.4l3.2 1.8-2.6 2.4z"/>'),
  'disney+': SVG('<text x="12" y="17" text-anchor="middle" font-size="13" font-weight="800" font-style="italic" font-family="serif">D+</text>'),
  'hbo max': SVG('<text x="12" y="10" text-anchor="middle" font-size="8" font-weight="800" font-family="sans-serif" letter-spacing="1">HBO</text>' +
    '<text x="12" y="19" text-anchor="middle" font-size="10" font-weight="800" font-family="sans-serif">max</text>'),
  'spotify': SVG('<circle cx="12" cy="12" r="10"/><g fill="none" stroke="#1a9c47" stroke-width="1.7" stroke-linecap="round">' +
    '<path d="M6.3 9.4c4.2-1.3 8.5-1 12 1.2"/><path d="M7 12.7c3.5-1 6.9-.7 9.8.9"/><path d="M7.7 15.8c2.7-.7 5.3-.5 7.6.7"/></g>'),
};

let appsCargadas = false;
document.getElementById('detApps').addEventListener('toggle', async e => {
  if (!e.target.open || appsCargadas || !actual) return;
  const cont = document.getElementById('apps'), id = actual.id;
  cont.textContent = 'Cargando...';
  try {
    const apps = await (await fetch('/d/' + id + '/apps')).json();
    cont.textContent = '';
    apps.forEach(a => {
      const b = document.createElement('button');
      b.title = a.name;
      if (a.icon) {
        const img = document.createElement('img');
        img.src = '/d/' + id + '/icon/' + a.id; img.alt = a.name; img.loading = 'lazy';
        b.appendChild(img);
      } else {
        b.style.background = a.color || 'var(--btn)';
        const logo = LOGOS[a.name.toLowerCase()];
        if (logo) {  // logo propio (constante de arriba, no viene de la TV) y el nombre debajo
          b.style.flexDirection = 'column'; b.style.gap = '3px';
          b.innerHTML = logo;
          const t = document.createElement('span');
          t.textContent = a.name; t.style.fontSize = '11px'; t.style.fontWeight = '600';
          b.appendChild(t);
        } else { b.textContent = a.name; b.style.fontSize = '14px'; b.style.fontWeight = '600'; }
      }
      b.addEventListener('click', async () => {
        try {
          const r = await fetch('/d/' + id + '/launch/' + encodeURIComponent(a.id), { method: 'POST' });
          if (!r.ok) throw new Error(await r.text());
          setEstado('Abriendo ' + a.name);
        } catch (e) { setEstado(msgError(e, 'No se pudo abrir ' + a.name), true); }
      });
      cont.appendChild(b);
    });
    appsCargadas = true;
  } catch { cont.textContent = 'No se pudieron cargar las apps'; }
});
