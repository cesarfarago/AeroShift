# ⚙️ AeroShift — Backend Core

> **Motor de transferência multi-cloud de alta performance em Kotlin assíncrono (Ktor + Coroutines).**

O módulo **backend** é o núcleo de orquestração do AeroShift. Ele gerencia autenticação OAuth 2.0 PKCE, descoberta recursiva de arquivos, concorrência, controle de taxa de requisições (rate limiting) e transferência de dados *in-memory* via streaming HTTP de alta velocidade.

---

## 🏛️ Diretrizes Arquiteturais

1. **Zero-Disk Cache (Processamento em Memória):** Nenhum arquivo ou fragmento de arquivo é salvo no disco local. O fluxo conecta o `ByteReadChannel` de download ao corpo de requisição de upload através de buffers em memória RAM.
2. **Clean Architecture (Hexagonal):** O motor de transferência ([`TransferEngine`](file:///Users/cesarfarago/Github/AeroShift/backend/src/main/kotlin/com/aeroshift/engine/TransferEngine.kt)) comunica-se exclusivamente com a porta de domínio [`ICloudStorageProvider`](file:///Users/cesarfarago/Github/AeroShift/backend/src/main/kotlin/com/aeroshift/domain/port/ICloudStorageProvider.kt). As implementações de provedores específicos ([`GoogleDriveProvider`](file:///Users/cesarfarago/Github/AeroShift/backend/src/main/kotlin/com/aeroshift/connectors/googledrive/GoogleDriveProvider.kt) e [`OneDriveProvider`](file:///Users/cesarfarago/Github/AeroShift/backend/src/main/kotlin/com/aeroshift/connectors/onedrive/OneDriveProvider.kt)) são adaptadores intercambiáveis.
3. **Resiliência e Retomada de Uploads (Resumable Uploads):**
   - Suporte a upload em blocos (chunks) usando as APIs oficiais de sessão de upload de cada nuvem.
   - Sincronização automática do offset de destino com consulta remota de status: se a rede oscilar, o pipeline descobre exatamente quantos bytes o destino já aceitou e continua sem corromper ou reenviar o arquivo.
   - Políticas de repetição com backoff exponencial tratando falhas de I/O, `UnresolvedAddressException` (DNS transitório), timeouts de requisição e erros de rate limit (`HTTP 429` / `503`).
4. **Gerenciamento Eficiente de Memória:**
   - [`ChunkBufferPool`](file:///Users/cesarfarago/Github/AeroShift/backend/src/main/kotlin/com/aeroshift/engine/ChunkBufferPool.kt) gerencia buffers pré-alocados para evitar pressão sobre o Garbage Collector (GC) e eliminar riscos de `OutOfMemoryError` (OOM).

---

## 📂 Estrutura de Pacotes

```
backend/src/main/kotlin/com/aeroshift/
├── Application.kt                     # Ponto de entrada (Servidor Ktor Netty na porta 8080)
├── auth/
│   ├── OAuthProviders.kt             # Configurações de endpoints e escopos OAuth do Google e Microsoft
│   └── PkceOAuthClient.kt            # Implementação de fluxo seguro OAuth 2.0 Authorization Code com PKCE
├── domain/
│   ├── model/
│   │   └── CloudModels.kt            # Modelos de domínio (CloudFile, CloudFolder, CloudEntry, etc.)
│   ├── port/
│   │   └── ICloudStorageProvider.kt  # Interface do provedor de nuvem e de sessões de upload
│   └── error/
│       └── CloudException.kt         # Exceções tipadas de nuvem (RateLimit, NotFound, AuthExpired, etc.)
├── engine/
│   ├── ChunkBufferPool.kt            # Pool de buffers de memória reutilizáveis
│   ├── ProgressTracker.kt            # Cálculo atômico de progresso, velocidade (MB/s), ETA e curadoria
│   ├── RetryPolicy.kt                # Estratégia de repetição com Exponential Backoff e tolerância a falhas
│   ├── StreamingPipeline.kt          # Pipeline in-memory de leitura/escrita por chunks com alinhamento de offset
│   ├── ThrottledProvider.kt          # Decorador para limitar taxa de requisições e evitar erros 429
│   └── TransferEngine.kt             # Orquestrador assíncrono com suporte a prioridade de fila por tamanho
├── connectors/
│   ├── common/
│   │   ├── HttpBodies.kt             # OutgoingContent otimizado (ByteArrayContent sem corrotinas desnecessárias)
│   │   └── HttpErrors.kt             # Mapeamento de status HTTP para exceções de domínio
│   ├── googledrive/
│   │   └── GoogleDriveProvider.kt    # Integração com a Google Drive API v3 (download + resumable upload)
│   └── onedrive/
│       └── OneDriveProvider.kt       # Integração com a Microsoft Graph API (download + uploadSession)
├── plugins/
│   ├── HTTP.kt                       # Configuração de CORS para a SPA
│   ├── Routing.kt                    # Registro central de rotas
│   └── Serialization.kt              # Negociação de conteúdo via kotlinx.serialization (JSON)
└── routes/
    ├── AuthRoutes.kt                 # Rotas de início, callback e checagem de status OAuth
    ├── Dependencies.kt               # Injeção de dependências e configuração do HttpClient CIO
    └── TransferRoutes.kt             # Endpoints REST e SSE para início, monitoramento e cancelamento
```

---

## 🛠️ Tecnologias Utilizadas

- **Kotlin 2.0.21** + **JVM 17**
- **Ktor Server 3.0.1** (Engine Netty para a API REST e SSE)
- **Ktor Client 3.0.1** (Engine CIO de alta performance para conexões HTTP com as nuvens)
- **Kotlinx Coroutines 1.9.0** (`async`, `Channel`, `Flow`)
- **Kotlinx Serialization 1.7.3** (JSON)
- **Logback Classic 1.5.12** (Logging estruturado)

---

## 🔌 Referência dos Endpoints da API

### Autenticação (`/auth`)

| Método | Rota | Descrição |
|---|---|---|
| `GET` | `/auth/{provider}/start` | Inicia o fluxo PKCE e redireciona o navegador para a tela de login do provedor (`google` ou `microsoft`). |
| `GET` | `/auth/{provider}/callback` | Callback de autorização que troca o código temporário por tokens de acesso/refresh. |
| `GET` | `/auth/{provider}/status` | Retorna `{"connected": true/false}` para verificar se o token do provedor está ativo. |

### Arquivos e Pastas (`/api/files`)

| Método | Rota | Descrição |
|---|---|---|
| `GET` | `/api/files/{provider}?folderId={id}` | Lista os arquivos e pastas da pasta informada (ou raiz se omitido). |
| `POST` | `/api/files/{provider}/folder` | Cria uma nova pasta no provedor (`body: {"name": "...", "parentId": "..."}`). |

### Transferência e Migração (`/api/transfer`)

| Método | Rota | Descrição |
|---|---|---|
| `POST` | `/api/transfer` | Inicia uma nova migração. Recebe provedores de origem/destino, itens selecionados, pasta destino, opções de curadoria e ordem de processamento. |
| `GET` | `/api/transfer/{id}/status` | **Server-Sent Events (SSE):** Stream contínuo do progresso em tempo real até a conclusão. |
| `POST` | `/api/transfer/{id}/cancel` | Interrompe imediatamente a transferência em execução. |

#### Exemplo de Payload para `POST /api/transfer`:
```json
{
  "sourceProvider": "google",
  "destinationProvider": "microsoft",
  "entries": [
    {
      "id": "1A2B3C...",
      "name": "Documentos",
      "isFolder": true,
      "sizeBytes": 0
    }
  ],
  "destinationFolderId": "01ABCDEF...",
  "skipEmptyFiles": true,
  "skipDuplicates": true,
  "processingOrder": "SMALLEST_FIRST"
}
```

*Valores aceitos para `processingOrder`:*
- `SMALLEST_FIRST` (Padrão: arquivos menores processados primeiro)
- `LARGEST_FIRST` (Arquivos maiores priorizados no início)
- `NATURAL` (Ordem natural de descoberta na árvore de diretórios)

---

## 🏃 Como Compilar e Executar

### Variáveis de Ambiente Necessárias
```bash
export GOOGLE_CLIENT_ID="seu_client_id_google"
export GOOGLE_CLIENT_SECRET="seu_client_secret_google" # opcional

export MICROSOFT_CLIENT_ID="seu_client_id_microsoft"
export MICROSOFT_CLIENT_SECRET="seu_client_secret_microsoft" # opcional
```

### Inicialização do Servidor
```bash
./gradlew run
```
O servidor estará pronto para receber conexões em `http://localhost:8080`.

### Execução da Suíte de Testes
```bash
./gradlew test
```
A suíte inclui testes unitários e de integração utilizando `FakeProvider` e `MockEngine` do Ktor para validar corrotinas, retry policies, streaming e lógica do motor de transferência.

---

## 👤 Autor

**Cesar Farago**  
- **GitHub:** [@cesarfarago](https://github.com/cesarfarago)  
- **Repositório:** [https://github.com/cesarfarago/AeroShift](https://github.com/cesarfarago/AeroShift)

---

## 📄 Licença & Direitos

Copyright (c) 2026 **Cesar Farago**. Todos os direitos reservados.  
Distribuído sob a licença MIT.
