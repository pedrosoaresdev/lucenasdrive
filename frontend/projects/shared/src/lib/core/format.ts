import { Pipe, PipeTransform } from '@angular/core';
import { Instantish, RideStatus } from './models';

const BRL = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' });

export function money(cents: number | null | undefined): string {
  return cents === null || cents === undefined ? '—' : BRL.format(cents / 100);
}

export function toDate(v: Instantish): Date | null {
  if (v === null || v === undefined || v === '') return null;
  return new Date(v);
}

export function fmtDateTime(v: Instantish): string {
  const d = toDate(v);
  return d ? d.toLocaleString('pt-BR', { dateStyle: 'short', timeStyle: 'short' }) : '—';
}

export function fmtTime(v: Instantish): string {
  const d = toDate(v);
  return d ? d.toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' }) : '';
}

export function km(meters: number | null | undefined): string {
  return meters === null || meters === undefined ? '—' : (meters / 1000).toLocaleString('pt-BR', { maximumFractionDigits: 1 }) + ' km';
}

export function minutes(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined) return '—';
  const m = Math.max(1, Math.round(seconds / 60));
  return m < 60 ? `${m} min` : `${Math.floor(m / 60)} h ${m % 60} min`;
}

export const RIDE_STATUS_LABEL: Record<RideStatus, string> = {
  REQUESTED: 'Solicitada',
  SEARCHING_DRIVER: 'Procurando motorista',
  ACCEPTED: 'Motorista confirmado',
  DRIVER_ARRIVING: 'Motorista a caminho',
  DRIVER_ARRIVED: 'Motorista no local',
  IN_PROGRESS: 'Em viagem',
  COMPLETED: 'Concluída',
  CANCELLED: 'Cancelada',
};

export function rideStatusTone(s: RideStatus): 'ok' | 'warn' | 'err' | 'info' | 'brand' {
  if (s === 'COMPLETED') return 'ok';
  if (s === 'CANCELLED') return 'err';
  if (s === 'SEARCHING_DRIVER' || s === 'REQUESTED') return 'warn';
  if (s === 'IN_PROGRESS') return 'brand';
  return 'info';
}

export const PAYMENT_LABEL: Record<string, string> = {
  CASH: 'Dinheiro', PIX: 'Pix', CARD: 'Cartão',
  PENDING: 'Pendente', CASH_DECLARED: 'Pago em dinheiro ao motorista', AWAITING_PROVIDER: 'Aguardando confirmação',
  CONFIRMED: 'Pagamento confirmado', FAILED: 'Falha no pagamento', REFUNDED: 'Reembolsado',
  CHARGEBACK: 'Contestado', NOT_CHARGED: 'Sem cobrança',
};

@Pipe({ name: 'money' })
export class MoneyPipe implements PipeTransform {
  transform(v: number | null | undefined): string { return money(v); }
}

@Pipe({ name: 'dt' })
export class DateTimePipe implements PipeTransform {
  transform(v: Instantish): string { return fmtDateTime(v); }
}

@Pipe({ name: 'km' })
export class KmPipe implements PipeTransform {
  transform(v: number | null | undefined): string { return km(v); }
}

@Pipe({ name: 'minutes' })
export class MinutesPipe implements PipeTransform {
  transform(v: number | null | undefined): string { return minutes(v); }
}

@Pipe({ name: 'rideStatus' })
export class RideStatusPipe implements PipeTransform {
  transform(v: RideStatus | null | undefined): string { return v ? RIDE_STATUS_LABEL[v] : '—'; }
}

@Pipe({ name: 'payment' })
export class PaymentLabelPipe implements PipeTransform {
  transform(v: string | null | undefined): string { return v ? PAYMENT_LABEL[v] ?? v : '—'; }
}
