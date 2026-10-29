/** Salva um arquivo recebido da API (ex.: relatório CSV) no computador do usuário. */
export function saveBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 10_000);
}

/** Datas "AAAA-MM-DD" para o nome do arquivo e para a API (padrão: últimos 30 dias). */
export function period(from: string, to: string): { from: string; to: string } {
  const iso = (d: Date) => d.toISOString().slice(0, 10);
  const end = to || iso(new Date());
  const start = from || iso(new Date(Date.now() - 29 * 86_400_000));
  return { from: start, to: end };
}
