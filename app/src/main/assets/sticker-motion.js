/* Shared tactile motion. No network or dependencies. */
(() => {
  const reduce = matchMedia('(prefers-reduced-motion: reduce)');
  const active = new WeakMap();
  let activate = () => {}, audio, pageTransition, layoutFrame = 0;
  const spring = [
    { scale: '.92 .87', offset: 0 },
    { scale: '1.055 1.07', offset: .48 },
    { scale: '.985 .975', offset: .72 },
    { scale: '1 1', offset: 1 },
  ];
  function animate(element, frames, options) {
    if (!element || reduce.matches) return null;
    active.get(element)?.cancel();
    const animation = element.animate(frames, options);
    active.set(element, animation);
    return animation;
  }
  function pop(element) {
    return animate(element, spring, { duration: 430, easing: 'ease-out' });
  }
  function reveal(element) {
    return animate(element, [
      { opacity: .25, translate: '12px 0' },
      { opacity: 1, translate: '-2px 0', offset: .7 },
      { opacity: 1, translate: '0 0' },
    ], { duration: 290, easing: 'ease-out' });
  }
  function transition(update, kind = 'forward') {
    if (reduce.matches) { update(); return; }
    pageTransition?.skipTransition();
    document.documentElement.dataset.transition = kind;
    if (document.startViewTransition) {
      pageTransition = document.startViewTransition(update);
      pageTransition.ready.catch(() => {});
      pageTransition.finished.catch(() => {});
    } else {
      update();
      animate(document.querySelector('#main'), [
        { opacity: 0, translate: `0 ${kind === 'back' ? '-16px' : '26px'}`, scale: '.975' },
        { opacity: 1, translate: '0 -4px', scale: '1.008', offset: .7 },
        { opacity: 1, translate: '0 0', scale: '1' },
      ], { duration: 390, easing: 'ease-out' });
    }
  }
  function enterSheet(sheet, previousHeight = 0) {
    sheet.style.pointerEvents = '';
    delete sheet.dataset.closing;
    if (previousHeight) {
      const height = sheet.getBoundingClientRect().height;
      animate(sheet, [{ height: previousHeight + 'px' }, { height: height + 'px' }],
        { duration: 330, easing: 'cubic-bezier(.2,1.15,.3,1)' });
      animate(sheet.querySelector('#sheet-body'), [
        { opacity: 0, translate: '18px 0' },
        { opacity: 1, translate: '-3px 0', offset: .7 },
        { opacity: 1, translate: '0 0' },
      ], { duration: 330, easing: 'ease-out' });
    } else {
      animate(sheet, [
        { translate: '0 104%', scale: '.96 1' },
        { translate: '0 -12px', scale: '1.012 .985', offset: .65 },
        { translate: '0 5px', scale: '.997 1.004', offset: .83 },
        { translate: '0 0', scale: '1 1' },
      ], { duration: 470, easing: 'cubic-bezier(.18,.8,.32,1)' });
    }
    scheduleLayout();
  }
  function leaveSheet(sheet, immediate = false) {
    active.get(sheet)?.cancel();
    if (!sheet.open) return;
    if (immediate || reduce.matches) { sheet.close(); return; }
    sheet.dataset.closing = 'true';
    sheet.style.pointerEvents = 'none';
    const animation = animate(sheet, [
      { translate: '0 0', scale: '1' },
      { translate: '0 -5px', scale: '1.008', offset: .2 },
      { translate: '0 105%', scale: '.97' },
    ], { duration: 250, easing: 'cubic-bezier(.5,0,.75,.5)' });
    animation?.finished.then(() => {
      if (sheet.dataset.closing) sheet.close();
      delete sheet.dataset.closing;
      sheet.style.pointerEvents = '';
    }).catch(() => {});
  }
  function sound(kind = 'tap') {
    try {
      audio ||= new (window.AudioContext || window.webkitAudioContext)();
      if (audio.state === 'suspended') audio.resume();
      const now = audio.currentTime, osc = audio.createOscillator(), gain = audio.createGain();
      osc.type = 'sine';
      osc.frequency.setValueAtTime(kind === 'open' ? 680 : 520, now);
      osc.frequency.exponentialRampToValueAtTime(kind === 'open' ? 350 : 220, now + .065);
      gain.gain.setValueAtTime(0, now);
      gain.gain.linearRampToValueAtTime(.085, now + .004);
      gain.gain.exponentialRampToValueAtTime(.001, now + .07);
      osc.connect(gain); gain.connect(audio.destination);
      osc.start(now); osc.stop(now + .075);
    } catch { /* Sound support is optional; interaction never depends on it. */ }
  }
  const resized = new ResizeObserver(entries => entries.forEach(({ target }) => {
    if (!target.isConnected) resized.unobserve(target);
    else placeSelection(target);
  }));
  function placeSelection(group) {
    const selected = group.querySelector('[aria-pressed="true"]');
    if (!selected) return;
    let base = group.querySelector(':scope > .segmented-base');
    if (!base) {
      base = document.createElement('span');
      base.className = 'segmented-base';
      base.setAttribute('aria-hidden', 'true');
      group.prepend(base);
    }
    base.replaceChildren(...[...group.querySelectorAll('.sticker-btn')].map(button => {
      const fill = document.createElement('span');
      Object.assign(fill.style, {left:button.offsetLeft+'px',top:button.offsetTop+'px',width:button.offsetWidth+'px',height:button.offsetHeight+'px'});
      return fill;
    }));
    group.style.setProperty('--choice-left', selected.offsetLeft + 'px');
    group.style.setProperty('--choice-top', selected.offsetTop + 'px');
    group.style.setProperty('--choice-width', selected.offsetWidth + 'px');
    group.style.setProperty('--choice-height', selected.offsetHeight + 'px');
  }
  function decorate() {
    document.querySelectorAll('.segmented').forEach(group => {
      placeSelection(group);
      resized.observe(group);
    });
  }
  function scheduleLayout() {
    if (layoutFrame) return;
    layoutFrame = requestAnimationFrame(() => { layoutFrame = 0; decorate(); });
  }
  let pressed;
  document.addEventListener('pointerdown', event => {
    const button = event.target.closest('button:not(:disabled)');
    if (!button || button.closest('#reader')) return;
    pressed = { button, x: event.clientX, y: event.clientY };
    animate(button, [{ scale: '1' }, { scale: '.92 .87' }],
      { duration: 105, fill: 'forwards', easing: 'ease-out' });
  }, { passive: true });
  document.addEventListener('pointermove', event => {
    if (pressed && Math.hypot(event.clientX-pressed.x, event.clientY-pressed.y)>12) {
      active.get(pressed.button)?.cancel(); pressed = null;
    }
  }, { passive: true });
  document.addEventListener('pointerup', () => { if (pressed) pop(pressed.button); pressed = null; });
  document.addEventListener('pointercancel', () => { if (pressed) active.get(pressed.button)?.cancel(); pressed = null; });
  document.addEventListener('click', event => {
    const button = event.target.closest('button:not(:disabled)');
    if (!button || button.closest('#reader')) return;
    if (!event.detail) pop(button);
    activate(button);
  }, true);
  function start() {
    decorate();
    new MutationObserver(records => {
      if (records.some(r => r.type === 'attributes' || [...r.addedNodes].some(n =>
        n.nodeType === 1 && (n.matches('.segmented') || n.querySelector('.segmented'))))) scheduleLayout();
    }).observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['data-page','aria-pressed','open'] });
  }
  if (document.readyState === 'loading') addEventListener('DOMContentLoaded', start);
  else start();
  window.StickerUI = { pop, reveal, transition, cancelTransition: () => pageTransition?.skipTransition(), enterSheet, leaveSheet, sound, refresh: scheduleLayout,
    onActivate(callback) { activate = callback; }, reducedMotion: () => reduce.matches };
})();
