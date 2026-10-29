/**
 * Roteiro de aceitação ponta a ponta do Lucena Drive (ambiente de testes local).
 *
 * Pré-requisitos (ver docs/INSTALL.md): Firebase Emulator Suite, backend (perfil dev) com o
 * proprietário provisionado (dono@lucena.test) e os portais em 4200/4201/4202.
 *   node e2e/acceptance.cjs
 *
 * Todos os dados são fictícios. Sem chave do Google Maps, os endereços são informados por coordenadas
 * e a rota é a estimativa aproximada (explicitamente sinalizada na interface).
 */
const { chromium } = require('playwright');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');

const PAX = process.env.PAX_URL || 'http://127.0.0.1:4200';
const DRV = process.env.DRV_URL || 'http://127.0.0.1:4201';
const ADM = process.env.ADM_URL || 'http://127.0.0.1:4202';
const AUTH_EMU = process.env.AUTH_EMU || 'http://127.0.0.1:9099';
const PROJECT = 'demo-lucena-drive';
const OUT = process.env.SHOTS || path.join(__dirname, 'screenshots');
const OWNER = { email: process.env.OWNER_EMAIL || 'dono@lucena.test', password: process.env.OWNER_PASSWORD || 'SenhaDono2026x' };
const RUN = Date.now().toString(36);
// Centro fictício único por execução (execuções repetidas não compartilham polígonos).
const LAT0 = -3 - (Math.floor(Date.now() / 1000) % 120) * 0.25;
const LNG0 = -40;
const fmt = (lat, lng) => `${lat.toFixed(4)}, ${lng.toFixed(4)}`;
const ORIGIN = fmt(LAT0, LNG0);
const DEST = fmt(LAT0 - 0.012, LNG0 + 0.015);

fs.mkdirSync(OUT, { recursive: true });
const results = [];
function step(name, ok, detail = '') {
  results.push({ name, ok, detail });
  console.log(`${ok ? '✔' : '✘'} ${name}${detail ? ' — ' + detail : ''}`);
  if (!ok) throw new Error(name + ' ' + detail);
}

// ---------------------------------------------------------------- TOTP (RFC 6238)
function base32Decode(s) {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  let bits = 0, value = 0;
  const out = [];
  for (const c of s.replace(/[\s=]/g, '').toUpperCase()) {
    value = (value << 5) | alphabet.indexOf(c);
    bits += 5;
    if (bits >= 8) { out.push((value >>> (bits - 8)) & 0xff); bits -= 8; }
  }
  return Buffer.from(out);
}
function totp(secret, step) {
  const buf = Buffer.alloc(8);
  buf.writeBigUInt64BE(BigInt(step));
  const h = crypto.createHmac('sha1', base32Decode(secret)).update(buf).digest();
  const o = h[h.length - 1] & 0xf;
  const n = ((h[o] & 0x7f) << 24) | (h[o + 1] << 16) | (h[o + 2] << 8) | h[o + 3];
  return String(n % 1_000_000).padStart(6, '0');
}
const currentStep = () => Math.floor(Date.now() / 1000 / 30);
async function nextFreshCode(secret, lastStep) {
  while (currentStep() <= lastStep) await new Promise((r) => setTimeout(r, 1000));
  return { code: totp(secret, currentStep()), step: currentStep() };
}

// ---------------------------------------------------------------- utilitários
const pdf = (name) => ({ name, mimeType: 'application/pdf', buffer: Buffer.from('%PDF-1.4\n% documento fictício de teste\n%%EOF\n') });
function cpf(seed) {
  const base = String(100000000 + (seed % 899999999)).slice(0, 9);
  const dv = (s, w) => { let t = 0; for (let i = 0; i < s.length; i++) t += Number(s[i]) * (w - i); const r = (t * 10) % 11; return r === 10 ? 0 : r; };
  const d1 = dv(base, 10);
  return base + d1 + dv(base + d1, 11);
}
async function verifyEmailViaEmulator(email) {
  const r = await fetch(`${AUTH_EMU}/emulator/v1/projects/${PROJECT}/oobCodes`).then((x) => x.json());
  const code = r.oobCodes.filter((c) => c.email === email && c.requestType === 'VERIFY_EMAIL').at(-1);
  if (!code) throw new Error('link de verificação não encontrado no emulador');
  await fetch(code.oobLink.replace('127.0.0.1', '127.0.0.1'));
}
async function confirmReason(page, reason = 'Validação do roteiro de aceitação') {
  const dlg = page.locator('admin-reason-dialog form');
  await dlg.waitFor();
  if (await dlg.locator('#rd-reason').count()) await dlg.locator('#rd-reason').fill(reason);
  await dlg.locator('button[type=submit]').click();
  await dlg.waitFor({ state: 'detached' });
}
async function toastText(page) {
  return page.locator('ld-toasts .toast').last().innerText({ timeout: 10000 }).catch(() => '');
}

(async () => {
  const browser = await chromium.launch();
  const geo = { latitude: LAT0 + 0.001, longitude: LNG0 + 0.001 };
  const admCtx = await browser.newContext({ viewport: { width: 1360, height: 900 }, locale: 'pt-BR' });
  const drvCtx = await browser.newContext({ viewport: { width: 420, height: 860 }, locale: 'pt-BR', geolocation: geo, permissions: ['geolocation'] });
  const paxCtx = await browser.newContext({ viewport: { width: 420, height: 860 }, locale: 'pt-BR', geolocation: { latitude: LAT0, longitude: LNG0 }, permissions: ['geolocation'] });
  const adm = await admCtx.newPage();
  const drv = await drvCtx.newPage();
  const pax = await paxCtx.newPage();
  for (const [n, p] of [['admin', adm], ['motorista', drv], ['passageiro', pax]]) {
    p.on('pageerror', (e) => console.log(`[${n}] erro de página:`, e.message));
  }
  // OpenStreetMap: neste ambiente de CI os servidores públicos são bloqueados; com E2E_MOCK_OSM=1 respondemos no
  // formato oficial (tiles PNG e GeoJSON do Photon). Sem a variável, o teste usa os serviços reais.
  if (process.env.E2E_MOCK_OSM === '1') {
    const tile = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8/5+hHgAHggJ/PchI7wAAAABJRU5ErkJggg==', 'base64');
    for (const ctx of [admCtx, drvCtx, paxCtx]) {
      await ctx.route('https://tile.openstreetmap.org/**', (r) => r.fulfill({ status: 200, contentType: 'image/png', body: tile }));
      await ctx.route('https://photon.komoot.io/**', (r) => r.fulfill({ status: 200, contentType: 'application/json',
        body: JSON.stringify({ type: 'FeatureCollection', features: [{ type: 'Feature',
          geometry: { type: 'Point', coordinates: [LNG0, LAT0] },
          properties: { countrycode: 'BR', street: 'Rua das Acácias', housenumber: '100', district: 'Centro',
            city: 'Cidade Teste', state: 'Paraíba', osm_id: '1', osm_type: 'N' } }] }) }));
    }
  }
  // Convite opcional de localização ("O olho de Deus"): quando aparecer, o usuário de teste permite.
  for (const p of [drv, pax]) {
    await p.addLocatorHandler(p.locator('ld-location-consent').getByRole('button', { name: 'Permitir' }),
      async (btn) => { await btn.click(); });
  }
  let lastStep = 0;
  let secret = null;
  /** Se o painel pedir confirmação de MFA recente (step-up), informa um código novo. */
  async function stepUpIfAsked() {
    const dlg = adm.locator('admin-step-up form');
    const shown = await dlg.waitFor({ timeout: 3000 }).then(() => true).catch(() => false);
    if (!shown) return;
    const fresh = await nextFreshCode(secret, lastStep);
    lastStep = fresh.step;
    await dlg.locator('input[aria-label="Código do autenticador"]').fill(fresh.code);
    await dlg.getByRole('button', { name: 'Confirmar' }).click();
    await dlg.waitFor({ state: 'detached' });
  }
  try {
    // ------------------------------------------------ Admin: login + MFA (TOTP)
    await adm.goto(`${ADM}/entrar`);
    await adm.fill('#email', OWNER.email);
    await adm.fill('#password', OWNER.password);
    await adm.click('button[type=submit]');
    await adm.waitForURL('**/mfa');
    const genBtn = adm.getByRole('button', { name: 'Gerar chave' });
    await Promise.race([genBtn.waitFor(), adm.locator('#code').waitFor()]);
    if (await genBtn.isVisible()) {
      await adm.getByRole('button', { name: 'Gerar chave' }).click();
      secret = (await adm.locator('.secret').innerText()).replace(/\s/g, '');
      fs.writeFileSync(path.join(OUT, '.owner-totp-secret'), secret);
      lastStep = currentStep();
      await adm.fill('input[aria-label="Código de 6 dígitos"]', totp(secret, lastStep));
      await adm.getByRole('button', { name: 'Confirmar' }).click();
      await adm.locator('ol.codes li').first().waitFor();
      step('Admin cadastra autenticador TOTP e recebe códigos de recuperação', (await adm.locator('ol.codes li').count()) === 10);
      await adm.screenshot({ path: path.join(OUT, '01-admin-recovery-codes.png') });
      await adm.locator('label.check input').check();
      await adm.getByRole('button', { name: 'Continuar' }).click();
    } else {
      secret = fs.readFileSync(path.join(OUT, '.owner-totp-secret'), 'utf8');
    }
    const fresh = await nextFreshCode(secret, lastStep);
    lastStep = fresh.step;
    await adm.fill('#code', fresh.code);
    await adm.getByRole('button', { name: 'Verificar' }).click();
    await adm.waitForURL('**/painel');
    await adm.locator('.kpi').first().waitFor();
    step('Admin entra com segundo fator e vê o dashboard', true);

    // ------------------------------------------------ Admin: cidade, área, tarifa, cupom
    await adm.goto(`${ADM}/geografia`);
    await adm.getByText('Cadastrar município').click();
    const cityName = 'Cidade Teste ' + RUN;
    await adm.fill('input[aria-label="Nome"]', cityName);
    await adm.fill('input[aria-label="UF"]', 'PB');
    await adm.locator('ld-place-search input').first().fill(ORIGIN);
    await adm.getByRole('button', { name: 'Cadastrar', exact: true }).click();
    await adm.getByText('Cidade cadastrada.').waitFor();
    await adm.getByText('Nova área').click();
    await adm.fill('input[aria-label="Nome da área"]', 'Cobertura central');
    await adm.fill('#vtx', [fmt(LAT0 + 0.1, LNG0 - 0.1), fmt(LAT0 + 0.1, LNG0 + 0.1), fmt(LAT0 - 0.1, LNG0 + 0.1),
      fmt(LAT0 - 0.1, LNG0 - 0.1)].join('\n'));
    await adm.getByRole('button', { name: 'Usar vértices digitados' }).click();
    await adm.getByRole('button', { name: 'Salvar área' }).click();
    await adm.getByText('Área criada.').waitFor();
    step('Admin cadastra cidade e área de cobertura (polígono)', true);
    await adm.screenshot({ path: path.join(OUT, '02-admin-geografia.png') });

    // Edição de região: renomear e ajustar (mesmo polígono, vértices carregados para edição)
    const areaRow = adm.locator('.item', { hasText: 'Cobertura central' });
    await areaRow.getByRole('button', { name: 'Editar' }).click();
    await adm.getByText('Editando: Cobertura central').waitFor();
    await adm.fill('input[aria-label="Nome da área"]', 'Cobertura central ampliada');
    await adm.getByRole('button', { name: 'Salvar alterações' }).click();
    await confirmReason(adm, 'Ajuste do nome da cobertura');
    await stepUpIfAsked();
    await adm.getByText('Área atualizada.').waitFor({ timeout: 15000 });
    await adm.locator('.item', { hasText: 'Cobertura central ampliada' }).waitFor();
    await adm.screenshot({ path: path.join(OUT, '02b-admin-geografia-edicao.png'), fullPage: true });
    step('Admin edita a região cadastrada e salva (auditado)', true);

    await adm.goto(`${ADM}/tarifas`);
    await adm.locator('select[aria-label="Cidade"]').selectOption({ label: `${cityName} - PB` });
    await adm.locator('section', { hasText: 'Lucena Essencial' }).getByRole('button', { name: 'Configurar' }).click();
    await adm.getByRole('button', { name: 'Salvar nova versão' }).click();
    await confirmReason(adm, 'Tarifa de lançamento da cidade de teste');
    await adm.getByText('Nova versão tarifária salva.').waitFor();
    await adm.locator('section', { hasText: 'Lucena Essencial' }).getByText(/v\d+ · ativa/).waitFor();
    step('Admin configura tarifa (nova versão registrada com motivo)', true);

    const coupon = ('BEMVINDO' + RUN.slice(-4)).toUpperCase().replace(/[^A-Z0-9]/g, '');
    await adm.goto(`${ADM}/cupons`);
    await adm.getByText('Criar campanha').click();
    await adm.locator('label', { hasText: 'Código' }).locator('input').fill(coupon);
    await adm.locator('label', { hasText: 'Descrição' }).locator('input').fill('Primeira viagem');
    await adm.locator('label', { hasText: 'Desconto (%)' }).locator('input').fill('10');
    await adm.getByRole('button', { name: 'Criar cupom' }).click();
    await adm.getByText('Cupom criado.').waitFor();
    step('Admin cria cupom', true, coupon);

    // ------------------------------------------------ Motorista: cadastro e documentos
    const drvEmail = `motorista.${RUN}@exemplo.test`;
    await drv.goto(`${DRV}/cadastro`);
    await drv.fill('#n', 'Motorista Teste Silva');
    await drv.fill('#cpf', cpf(Date.now()));
    await drv.fill('#b', '1988-03-10');
    await drv.fill('#p', '(83) 98888-7777');
    await drv.fill('#cnh', '12345678901');
    await drv.fill('#exp', '2031-12-31');
    await drv.locator('label.check', { hasText: 'EAR' }).locator('input').check();
    await drv.locator('#city').selectOption({ label: `${cityName} - PB` });
    await drv.fill('#e', drvEmail);
    await drv.fill('#pw', 'SenhaMotorista2026');
    await drv.fill('#pw2', 'SenhaMotorista2026');
    await drv.locator('label.check', { hasText: 'Termos' }).locator('input').check();
    await drv.locator('label.check', { hasText: 'Política' }).locator('input').check();
    await drv.getByRole('button', { name: 'Continuar' }).click();
    await drv.waitForURL('**/documentos', { timeout: 20000 });
    step('Motorista se cadastra (conta + CNH + cidade)', true);
    await drv.fill('input[aria-label="Marca"]', 'Fabricante');
    await drv.fill('input[aria-label="Modelo"]', 'Sedã Teste');
    await drv.fill('input[aria-label="Ano"]', '2022');
    await drv.fill('input[aria-label="Cor"]', 'Prata');
    const plate = 'TST' + (Date.now() % 10) + 'A' + String(Date.now() % 100).padStart(2, '0');
    await drv.fill('input[aria-label="Placa"]', plate);
    await drv.getByRole('button', { name: 'Salvar veículo' }).click();
    await drv.getByText('Veículo cadastrado.').waitFor();
    const uploads = [['CRLV do veículo', 'crlv.pdf'], ['CNH (frente e verso)', 'cnh.pdf'], ['Certidão de antecedentes', 'antecedentes.pdf'],
      ['Foto de perfil (rosto)', 'foto.pdf']];
    for (const [label, file] of uploads) {
      await drv.locator('.doc', { hasText: label }).locator('input[type=file]').setInputFiles(pdf(file));
      await drv.locator('.doc', { hasText: label }).getByText('em análise').waitFor({ timeout: 20000 });
    }
    step('Motorista envia documentos para área privada do Storage', true);
    await drv.getByRole('button', { name: 'Enviar para análise' }).click();
    await drv.getByText('Pendente de análise').first().waitFor();
    await drv.screenshot({ path: path.join(OUT, '03-motorista-documentos.png') });
    step('Motorista envia cadastro para análise', true);

    // ------------------------------------------------ Admin aprova
    await adm.goto(`${ADM}/motoristas`);
    await adm.locator('tbody tr').first().waitFor();
    await adm.locator('tbody tr').first().click();
    await adm.getByRole('heading', { name: 'Motorista Teste Silva' }).waitFor();
    for (let i = 0; i < 4; i++) {
      const btn = adm.locator('.doc').getByRole('button', { name: 'Aprovar', exact: true }).first();
      await btn.click();
      await adm.getByText(/Documento aprovado|Veículo aprovado/).last().waitFor();
      await adm.waitForTimeout(400);
    }
    // veículo
    const vehicleApprove = adm.locator('section', { hasText: 'Veículos' }).getByRole('button', { name: 'Aprovar', exact: true });
    if (await vehicleApprove.count()) {
      await vehicleApprove.first().click();
      await adm.waitForTimeout(600);
    }
    await adm.getByRole('button', { name: 'Aprovar motorista' }).click();
    await confirmReason(adm, 'Documentação conferida');
    await adm.getByText('Aprovado').first().waitFor();
    await adm.screenshot({ path: path.join(OUT, '04-admin-aprova-motorista.png') });
    step('Admin aprova documentos, veículo e motorista', true);

    // ------------------------------------------------ Motorista online
    await drv.goto(`${DRV}/painel`);
    const sw = drv.getByRole('switch', { name: 'Disponibilidade' });
    await sw.waitFor();
    await sw.click();
    await drv.getByText('Você está online', { exact: true }).waitFor();
    step('Motorista fica online com localização autorizada', true);

    // ------------------------------------------------ Passageiro: cadastro + verificação de e-mail
    const paxEmail = `passageira.${RUN}@exemplo.test`;
    const paxPassword = 'SenhaPassageira2026';
    await pax.goto(`${PAX}/cadastro`);
    await pax.fill('#name', 'Passageira Teste Lima');
    await pax.fill('#cpf', cpf(Date.now() + 7));
    await pax.fill('#birth', '1996-05-20');
    await pax.fill('#phone', '(83) 97777-6666');
    await pax.fill('#email', paxEmail);
    await pax.fill('#pw', paxPassword);
    await pax.fill('#pw2', paxPassword);
    await pax.locator('label.check', { hasText: 'Termos' }).locator('input').check();
    await pax.locator('label.check', { hasText: 'Política' }).locator('input').check();
    await pax.getByRole('button', { name: 'Criar conta' }).click();
    await pax.waitForURL('**/verificar-email', { timeout: 20000 });
    await verifyEmailViaEmulator(paxEmail);
    await pax.getByRole('button', { name: 'Já confirmei' }).click();
    await pax.waitForURL('**/inicio', { timeout: 20000 });
    step('Passageiro se cadastra e confirma o e-mail', true);

    // ------------------------------------------------ Cotação, cupom e solicitação
    // Busca de endereço pelo OpenStreetMap (Photon): digitar, escolher a sugestão
    await pax.locator('#origin').fill('Rua das Acácias 100');
    await pax.getByRole('option', { name: /Rua das Acácias, 100/ }).click({ timeout: 15000 });
    await pax.locator('.leaflet-container').first().waitFor({ timeout: 15000 });
    await pax.locator('.ld-osm-marker').first().waitFor({ timeout: 15000 });
    step('Mapa OpenStreetMap carregado e endereço encontrado pela busca (sem chave do Google)', true,
      'Rua das Acácias, 100 - Centro - Cidade Teste - Paraíba');
    await pax.locator('#destination').fill(DEST);
    await pax.locator('input[aria-label="Cupom de desconto"]').fill(coupon);
    await pax.getByRole('button', { name: 'Ver preços' }).click();
    await pax.locator('.opt').first().waitFor({ timeout: 20000 });
    const priceText = await pax.locator('.opt').first().innerText();
    step('Passageiro vê mapa/estimativa, preço calculado no servidor e cupom aplicado', /Cupom aplicado/.test(await pax.locator('section').innerText()), priceText.replace(/\s+/g, ' '));
    await pax.screenshot({ path: path.join(OUT, '05-passageiro-cotacao.png') });
    await pax.getByRole('button', { name: /^Solicitar/ }).click();
    await pax.waitForURL('**/corrida/**', { timeout: 20000 });
    const rideId = pax.url().split('/').pop();
    step('Passageiro solicita a corrida', true, rideId);

    // ------------------------------------------------ Motorista recebe e aceita (tempo real)
    await drv.locator('.offer').waitFor({ timeout: 30000 });
    await drv.screenshot({ path: path.join(OUT, '06-motorista-oferta.png') });
    await drv.getByRole('button', { name: 'Aceitar' }).click();
    await drv.waitForURL('**/corrida/**', { timeout: 20000 });
    step('Motorista recebe a oferta em tempo real e aceita', true);

    await pax.getByText('Motorista S.').waitFor({ timeout: 20000 });
    await pax.getByText(plate.slice(0, 3)).first().waitFor();
    step('Passageiro vê motorista, veículo e placa em tempo real', true);

    // ------------------------------------------------ Chat
    await pax.getByRole('button', { name: 'Chat com o motorista' }).click();
    await pax.locator('#chat-input').fill('Estou no portão azul');
    await pax.locator('#chat-input').press('Enter');
    await drv.getByRole('button', { name: 'Chat', exact: true }).click();
    await drv.locator('.bubble', { hasText: 'Estou no portão azul' }).waitFor({ timeout: 15000 });
    await drv.locator('#chat-input').fill('Chego em 2 minutos!');
    await drv.locator('#chat-input').press('Enter');
    await pax.locator('.bubble', { hasText: 'Chego em 2 minutos!' }).waitFor({ timeout: 15000 });
    await pax.locator('.sys', { hasText: 'Motorista aceitou a corrida.' }).waitFor();
    await pax.screenshot({ path: path.join(OUT, '07-passageiro-chat.png') });
    step('Chat em tempo real entre passageiro e motorista (com mensagens de sistema)', true);

    await drv.locator('ld-chat-panel').getByRole('button', { name: 'Fechar chat' }).click();
    await pax.locator('ld-chat-panel').getByRole('button', { name: 'Fechar chat' }).click();

    // ------------------------------------------------ Segurança: compartilhar viagem e alerta
    await pax.getByRole('button', { name: 'Segurança' }).click();
    await pax.getByRole('button', { name: 'Gerar link' }).click();
    const shareInput = pax.locator('input[aria-label="Link de acompanhamento"]');
    await shareInput.waitFor({ timeout: 15000 });
    const shareUrl = await shareInput.inputValue();
    const guestCtx = await browser.newContext({ viewport: { width: 420, height: 860 }, locale: 'pt-BR' });
    const guest = await guestCtx.newPage();
    await guest.goto(shareUrl);
    await guest.getByText('Veículo').waitFor({ timeout: 15000 });
    const guestText = await guest.locator('dl.trip').innerText();
    await guest.screenshot({ path: path.join(OUT, '07b-contato-acompanha-viagem.png') });
    await guestCtx.close();
    step('Contato de confiança acompanha a viagem pelo link (sem login, dados mínimos)', /[A-Z]{3}/.test(guestText), shareUrl.replace(/[^/]+$/, '…'));
    await pax.locator('textarea[aria-label="O que está acontecendo"]').fill('Teste do roteiro de aceitação (fictício).');
    await pax.getByRole('button', { name: 'Enviar alerta de segurança' }).click();
    await pax.getByRole('button', { name: 'Alerta enviado' }).waitFor({ timeout: 15000 });
    await pax.locator('ld-safety-panel').getByRole('button', { name: 'Fechar' }).click();
    step('Alerta de segurança registrado com prioridade máxima', true);

    // ------------------------------------------------ Execução da corrida
    for (const label of ['Iniciar deslocamento ao embarque', 'Cheguei ao embarque', 'Iniciar viagem', 'Finalizar viagem']) {
      await drv.getByRole('button', { name: label }).click();
      await drv.waitForTimeout(1200);
    }
    await drv.getByText('Viagem finalizada.').waitFor({ timeout: 15000 });
    step('Motorista executa a máquina de estados até a conclusão ("Cheguei" validado por proximidade)', true);

    // ------------------------------------------------ Avaliação recíproca (motorista → passageiro)
    const ratePax = drv.locator('app-rate-passenger');
    await ratePax.waitFor({ timeout: 15000 });
    await ratePax.getByRole('radio', { name: /^5 estrelas/ }).click();
    await ratePax.getByRole('button', { name: 'Educado' }).click();
    await ratePax.getByRole('button', { name: 'Enviar avaliação do passageiro' }).click();
    await drv.getByText('Avaliação do passageiro enviada').waitFor({ timeout: 15000 });
    await drv.screenshot({ path: path.join(OUT, '08b-motorista-avalia-passageiro.png') });
    step('Motorista avalia o passageiro (avaliação recíproca)', true);

    // ------------------------------------------------ Avaliação
    await pax.waitForURL('**/avaliar', { timeout: 20000 });
    await pax.getByRole('heading', { name: 'Como foi sua experiência com o Lucena Drive?' }).waitFor();
    await pax.getByRole('radio', { name: /^5 estrelas/ }).click();
    await pax.getByRole('button', { name: 'Direção segura' }).click();
    await pax.locator('#c').fill('Motorista muito educado.');
    await pax.screenshot({ path: path.join(OUT, '08-passageiro-avaliacao.png') });
    await pax.getByRole('button', { name: 'Enviar avaliação' }).click();
    await pax.getByText('Obrigado pela avaliação!').waitFor();
    step('Passageiro avalia com 5 estrelas', true);

    await drv.goto(`${DRV}/avaliacoes`);
    await drv.locator('.avg .value').waitFor();
    const avg = await drv.locator('.avg .value').innerText();
    step('Média do motorista atualizada no servidor', avg.trim() === '5.00', avg);

    // ------------------------------------------------ Admin consulta corrida e lançamentos
    await adm.goto(`${ADM}/corridas/${rideId}`);
    await adm.getByText('Lançamentos financeiros').waitFor();
    const ledgerRows = await adm.locator('section', { hasText: 'Lançamentos financeiros' }).locator('tbody tr').count();
    await adm.screenshot({ path: path.join(OUT, '09-admin-corrida.png'), fullPage: true });
    step('Admin consulta a corrida, comissão e lançamentos financeiros', ledgerRows >= 3, `${ledgerRows} lançamentos`);

    await adm.goto(`${ADM}/painel`);
    await adm.locator('.kpi', { hasText: 'Concluídas' }).waitFor();
    await adm.screenshot({ path: path.join(OUT, '10-admin-dashboard.png'), fullPage: true });
    step('Dashboard reflete os dados reais do banco', true);

    // ------------------------------------------------ O olho de Deus (localização opcional)
    await pax.goto(`${PAX}/perfil`);
    await pax.getByRole('button', { name: 'Desligar' }).waitFor({ timeout: 15000 }); // compartilhamento ativo
    await adm.goto(`${ADM}/olho-de-deus`);
    const eye = adm.locator('.list');
    await eye.getByText('Passageira Teste Lima').first().waitFor({ timeout: 30000 });
    await eye.getByText('Motorista Teste Silva').first().waitFor({ timeout: 30000 });
    await eye.getByText(cityName).first().waitFor({ timeout: 15000 }); // cidade identificada pela área de cobertura
    const eyeText = await eye.innerText();
    if (!/IP\s*\n?\s*\d/.test(eyeText) || !/Aparelho/.test(eyeText)) throw new Error('IP/aparelho ausentes no painel');
    await adm.locator('admin-map-tools select').selectOption({ label: `${cityName} - PB` });
    await eye.getByText('Passageira Teste Lima').first().waitFor({ timeout: 15000 });
    await adm.screenshot({ path: path.join(OUT, '10b-admin-olho-de-deus.png'), fullPage: true });
    step('Admin vê no "O olho de Deus" passageiro e motorista que ativaram a localização', true,
      'nome, tipo, cidade, IP e aparelho; filtro por cidade');

    // ------------------------------------------------ Nota do passageiro no perfil
    await pax.goto(`${PAX}/perfil`);
    const paxRating = pax.getByText(/Sua nota como passageiro/);
    await paxRating.waitFor({ timeout: 15000 });
    const paxRatingText = await paxRating.innerText();
    step('Passageiro vê a própria média (sem notas individuais)', paxRatingText.includes('5.00'), paxRatingText.trim());

    // ------------------------------------------------ Suporte com anexo
    await pax.goto(`${PAX}/suporte`);
    await pax.locator('#sub').fill(`Objeto esquecido ${RUN}`);
    await pax.locator('#desc').fill('Esqueci uma mochila preta no banco de trás (teste fictício).');
    await pax.locator('#att').setInputFiles(pdf('comprovante.pdf'));
    await pax.getByText('comprovante.pdf').waitFor();
    await pax.getByRole('button', { name: 'Enviar chamado' }).click();
    await pax.getByText(`Objeto esquecido ${RUN}`).waitFor({ timeout: 15000 });
    step('Passageiro abre chamado com anexo (Storage, só o dono envia)', true);

    await adm.goto(`${ADM}/suporte`);
    await adm.getByRole('button', { name: new RegExp(`Objeto esquecido ${RUN}`) }).click();
    const [download] = await Promise.all([
      adm.waitForEvent('download', { timeout: 20000 }),
      (async () => { await adm.getByRole('button', { name: /Ver anexo 1/ }).click(); await stepUpIfAsked(); })(),
    ]);
    await adm.screenshot({ path: path.join(OUT, '11-admin-suporte-anexo.png'), fullPage: true });
    step('Equipe acessa o anexo pelo backend (auditado)', download.suggestedFilename().endsWith('.pdf'), download.suggestedFilename());

    // ------------------------------------------------ LGPD: exportação e exclusão
    await pax.goto(`${PAX}/perfil`);
    const [exportFile] = await Promise.all([
      pax.waitForEvent('download', { timeout: 20000 }),
      (async () => {
        await pax.getByRole('button', { name: 'Baixar meus dados' }).click();
        // Login antigo (> 5 min): o servidor exige reautenticação e a tela pede a senha.
        const reauth = pax.locator('input[name=reauth]');
        if (await reauth.waitFor({ timeout: 3000 }).then(() => true).catch(() => false)) {
          await reauth.fill(paxPassword);
          await pax.getByRole('button', { name: 'Confirmar e baixar' }).click();
        }
      })(),
    ]);
    const exported = JSON.parse(fs.readFileSync(await exportFile.path(), 'utf8'));
    step('Titular baixa a cópia dos próprios dados', exported.account?.email === paxEmail
      && exported.ridesAsPassenger?.length === 1 && exported.supportTickets?.length === 2, exportFile.suggestedFilename());

    await pax.getByRole('button', { name: 'Excluir conta' }).click();
    await pax.getByRole('button', { name: 'Confirmar' }).click();
    await toastText(pax);
    await adm.goto(`${ADM}/privacidade-lgpd`);
    const delRow = adm.locator('tr', { hasText: 'Exclusão de conta' }).filter({ has: adm.getByRole('button', { name: 'Anonimizar conta' }) }).first();
    await delRow.getByRole('button', { name: 'Anonimizar conta' }).click();
    await confirmReason(adm, 'Pedido do titular confirmado no roteiro de aceitação');
    await stepUpIfAsked();
    const anonToast = await toastText(adm);
    await adm.screenshot({ path: path.join(OUT, '12-admin-lgpd-anonimizacao.png'), fullPage: true });
    step('Admin executa a anonimização (justificativa, MFA, auditoria)', anonToast.includes('Conta anonimizada'), anonToast);
  } catch (e) {
    console.error('FALHA:', e.message);
    for (const [n, p] of [['admin', adm], ['motorista', drv], ['passageiro', pax]]) {
      await p.screenshot({ path: path.join(OUT, `falha-${n}.png`), fullPage: true }).catch(() => undefined);
    }
    process.exitCode = 1;
  } finally {
    fs.writeFileSync(path.join(OUT, 'results.json'), JSON.stringify(results, null, 2));
    await browser.close();
  }
})();
