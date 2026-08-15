export interface ServiceRecord {
  id: string;
  ownerId: string;
  name: string;
  hostname?: string;
  platform?: string;
  arch?: string;
  agentVersion?: string;
  home?: string;
  createdAt: string;
  lastConnectedAt: string;
}

export interface ServiceView {
  id: string;
  name: string;
  hostname?: string;
  platform?: string;
  arch?: string;
  agentVersion?: string;
  home?: string;
  connectedAt?: string;
  runtime: {
    state: "connected" | "disconnected";
    message?: string;
  };
}

export interface AgentDescription {
  hostname: string;
  platform: string;
  arch: string;
  agentVersion: string;
  home: string;
  capabilities: string[];
}

export interface GatewayEvent {
  sequence: number;
  timestamp: string;
  ownerId: string;
  serviceId?: string;
  type: string;
  payload: unknown;
}

export type JsonRpcId = number | string;

export interface PendingServerRequest {
  requestId: string;
  rpcId: JsonRpcId;
  ownerId: string;
  serviceId: string;
  method: string;
  params: unknown;
  createdAt: string;
}

export interface AgentRequestMessage {
  type: "request";
  id: string;
  method: string;
  params: unknown;
}

export interface AgentResponseMessage {
  type: "response";
  id: string;
  ok: boolean;
  result?: unknown;
  error?: { code?: string; message: string; details?: unknown };
}

export interface AgentEventMessage {
  type: "event";
  event: string;
  payload: unknown;
}

export interface AgentStreamMessage {
  type: "stream" | "stream.end" | "stream.error";
  id: string;
  data?: string;
  message?: string;
}

export type AgentInboundMessage =
  | AgentResponseMessage
  | AgentEventMessage
  | AgentStreamMessage;
