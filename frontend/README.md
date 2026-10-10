# 🖥️ AeroShift — Frontend SPA

> **Interface gráfica moderna (React 19 + TypeScript + Vite) para migração entre nuvens com telemetria em tempo real.**

O módulo **frontend** do AeroShift é uma Single Page Application (SPA) construída para oferecer uma experiência de usuário visualmente atraente, fluida e intuitiva para o gerenciamento e migração de arquivos entre o **Google Drive** e o **Microsoft OneDrive**.

---

## 🎨 Design & Filosofia Visual

- **Glassmorphism & Dark Mode:** Painéis translúcidos com efeito de desfoque (`backdrop-filter: blur(16px)`), gradientes modernos e bordas sutis.
- **Micro-Interações e Responsividade:** Transições suaves em hover, botões com feedback tátil e layout adaptável a diferentes resoluções.
- **Tipografia Moderna:** Fontes sans-serif legíveis e hierarquia visual bem definida.
- **Zero Frameworks de CSS Pesados:** Construído inteiramente com **Vanilla CSS** moderno e variáveis semânticas CSS para máxima performance e velocidade de renderização.

---

## ✨ Funcionalidades da Interface

### 1. Conexão & Direção de Migração
- **Cartões de Provedores:** Exibição clara do status de autenticação (Conectado / Desconectado) para Google Drive e OneDrive.
- **Inversão Rápida de Direção (`⇄`):** Botão central circular que inverte instantaneamente o sentido da migração (Google Drive ➔ OneDrive ou OneDrive ➔ Google Drive) redefinindo os seletores de forma automática.

### 2. Explorador de Arquivos Inteligente (`FileExplorer`)
- **🔍 Pesquisa Instantânea em Tempo Real:** Campo de busca com ícone de lupa e botão para limpar (`✕`) que filtra imediatamente arquivos e pastas na visualização atual.
- **☑️ Checkboxes na 1ª Coluna:** Substituição do botão antigo textual por caixas de seleção na primeira coluna de cada linha, aumentando o espaço útil para visualização de nomes longos.
- **☑️ Checkbox Mestre no Cabeçalho:** Permite marcar ou desmarcar todos os itens exibidos da pasta atual com um único clique, com contador dinâmico de itens selecionados.
- **Navegação por Breadcrumbs:** Caminho de pastas clicável no topo para voltar rapidamente a qualquer nível da hierarquia.
- **Pastas no Destino:** Botão **`+ Nova Pasta`** para criar diretórios diretamente na nuvem destino sem sair da aplicação.

### 3. Painel de Opções de Migração
- **Ignorar Arquivos Vazios:** Filtra arquivos de tamanho 0 bytes antes de enviá-los.
- **Ignorar Arquivos Duplicados:** Evita o reenvio de arquivos com mesmo nome, tamanho e hash já existentes no destino.
- **Ordem de Processamento da Fila:**
  - `⚡ Menores primeiro (Rápido)`: Arquivos leves são enviados primeiro, acelerando o volume de documentos migrados de imediato.
  - `🐘 Maiores primeiro`: Arquivos pesados entram no início da fila de transferência.
  - `📁 Ordem da pasta`: Migra de acordo com a estrutura hierárquica natural de pastas.

### 4. Telemetria e Progresso em Tempo Real (SSE)
- **Barra de Progresso Dinâmica:** Porcentagem calculada em tempo real com base no total de bytes transferidos.
- **Métricas Vivas:**
  - Arquivos concluídos / total em fila.
  - Velocidade média e instantânea em Megabytes por segundo (`MB/s`).
  - Tempo estimado de conclusão (`ETA`).
- **Curadoria Inteligente:** Exibição destacada de quantos arquivos foram ignorados por serem vazios ou duplicados.
- **Relatório de Falhas Detalhado:** Listagem dos arquivos que apresentaram erro, acompanhados de descrição clara da causa (ex.: erro de permissão, instabilidade de rede).

---

## 📂 Estrutura de Arquivos

```
frontend/
├── index.html              # Ponto de entrada HTML da aplicação
├── package.json            # Dependências e scripts npm
├── tsconfig.json           # Configurações do compilador TypeScript
├── vite.config.ts          # Configuração do bundler Vite
└── src/
    ├── main.tsx            # Inicialização do React DOM
    ├── App.tsx             # Componente raiz, estados de migração, SSE e FileExplorer
    └── index.css           # Design System (tokens CSS, glassmorphism, animações e layout)
```

---

## 🛠️ Tecnologias Utilizadas

- **React 19**
- **TypeScript**
- **Vite 8**
- **Server-Sent Events (SSE)** nativo via `EventSource` da Web API
- **Oxlint** para análise estática de código de alta velocidade

---

## 🏃 Como Executar

### Instalação de Dependências
```bash
npm install
```

### Modo de Desenvolvimento (com Hot Module Replacement - HMR)
```bash
npm run dev
```
Acesse a aplicação no navegador em: `http://localhost:5173`.

### Verificação de Tipos e Build de Produção
```bash
npm run build
```
O pacote otimizado para produção será gerado na pasta `dist/`.

### Pré-visualização do Build Local
```bash
npm run preview
```

### Análise Estática (Lint)
```bash
npm run lint
```

---

## ⚙️ Configuração da API Backend

Por padrão, a SPA comunica-se com o backend local rodando em:
```typescript
const API_BASE = 'http://localhost:8080';
```
Para alterar o endereço da API em ambientes customizados ou em contêineres, ajuste a constante `API_BASE` em [src/App.tsx](file:///Users/cesarfarago/Github/AeroShift/frontend/src/App.tsx).

---

## 👤 Autor

**Cesar Farago**  
- **GitHub:** [@cesarfarago](https://github.com/cesarfarago)  
- **Repositório:** [https://github.com/cesarfarago/AeroShift](https://github.com/cesarfarago/AeroShift)

---

## 📄 Licença & Direitos

Copyright (c) 2026 **Cesar Farago**. Todos os direitos reservados.  
Distribuído sob a licença MIT.
