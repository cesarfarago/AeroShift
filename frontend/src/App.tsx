/**
 * AeroShift — Multi-Cloud File Migration & Synchronization
 * Author: Cesar Farago (https://github.com/cesarfarago)
 * Copyright (c) 2026 Cesar Farago. All rights reserved.
 */

import React, { useState, useEffect, useMemo } from 'react';
import './index.css';

const API_BASE = 'http://localhost:8080';

// Simple Icons as SVG components
const GoogleDriveIcon = () => (
  <svg width="24" height="24" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/240000/svg">
    <path d="M15.3 5H8.7L2.2 16.2H8.8L15.3 5Z" fill="#FFC107"/>
    <path d="M5.5 16.2L8.7 21.8H21.8L18.6 16.2H5.5Z" fill="#1976D2"/>
    <path d="M15.3 5L21.8 16.2H15.3L8.8 5H15.3Z" fill="#4CAF50"/>
  </svg>
);

const OneDriveIcon = () => (
  <svg width="24" height="24" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/240000/svg">
    <path d="M12 11C12 9.34315 10.6569 8 9 8C7.34315 8 6 9.34315 6 11H12Z" fill="#0078D4"/>
    <path d="M18 11C18 9.34315 16.6569 8 15 8C13.3431 8 12 9.34315 12 11H18Z" fill="#0078D4"/>
    <path d="M15 8C15 6.34315 13.6569 5 12 5C10.3431 5 9 6.34315 9 8H15Z" fill="#0078D4"/>
    <path d="M12 17C15.3137 17 18 14.3137 18 11H6C6 14.3137 8.68629 17 12 17Z" fill="#0078D4"/>
  </svg>
);

const FolderIcon = () => (
  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"></path>
  </svg>
);

const FileIcon = () => (
  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <path d="M13 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z"></path>
    <polyline points="13 2 13 9 20 9"></polyline>
  </svg>
);

interface CloudEntry {
  id: string;
  name: string;
  isFolder: boolean;
  sizeBytes: number;
}

function App() {
  const [googleStatus, setGoogleStatus] = useState<boolean>(false);
  const [microsoftStatus, setMicrosoftStatus] = useState<boolean>(false);
  const [transferId, setTransferId] = useState<string | null>(() => sessionStorage.getItem('aeroshift.transferId'));
  const [progress, setProgress] = useState<any>(null);
  const [selectedEntries, setSelectedEntries] = useState<CloudEntry[]>([]);
  const [destFolderId, setDestFolderId] = useState<string | null>(null);
  const [direction, setDirection] = useState<'google_to_microsoft' | 'microsoft_to_google'>('google_to_microsoft');
  const [skipEmptyFiles, setSkipEmptyFiles] = useState<boolean>(true);
  const [skipDuplicates, setSkipDuplicates] = useState<boolean>(true);
  const [processingOrder, setProcessingOrder] = useState<'SMALLEST_FIRST' | 'LARGEST_FIRST' | 'NATURAL'>('SMALLEST_FIRST');

  useEffect(() => {
    fetchStatus('google');
    fetchStatus('microsoft');
  }, []);

  useEffect(() => {
    if (transferId) {
      const source = new EventSource(`${API_BASE}/api/transfer/${transferId}/status`);
      let errors = 0;
      source.onerror = () => {
        // Transferência inexistente (backend reiniciado/expirada): para de reconectar.
        errors++;
        if (errors >= 5) {
          source.close();
          sessionStorage.removeItem('aeroshift.transferId');
          setTransferId(null);
          setProgress(null);
        }
      };
      source.onmessage = (event) => {
        errors = 0;
        const data = JSON.parse(event.data);
        setProgress(data);
        if (data.state === 'COMPLETED' || data.state === 'FAILED' || data.state === 'COMPLETED_WITH_ERRORS' || data.state === 'CANCELLED') {
          source.close();
        }
      };
      return () => source.close();
    }
  }, [transferId]);

  const fetchStatus = async (provider: string) => {
    try {
      const res = await fetch(`${API_BASE}/auth/${provider}/status`);
      const data = await res.json();
      if (provider === 'google') setGoogleStatus(data.authenticated);
      if (provider === 'microsoft') setMicrosoftStatus(data.authenticated);
    } catch (e) {
      console.error(`Failed to fetch status for ${provider}`);
    }
  };

  const login = (provider: string) => {
    const width = 600, height = 700;
    const left = (window.innerWidth - width) / 2;
    const top = (window.innerHeight - height) / 2;
    const authWindow = window.open(`${API_BASE}/auth/${provider}/start`, '_blank', `width=${width},height=${height},top=${top},left=${left}`);
    
    const timer = setInterval(() => {
      if (authWindow?.closed) {
        clearInterval(timer);
        fetchStatus(provider);
      }
    }, 1000);
  };

  const startTransfer = async () => {
    if (!googleStatus || !microsoftStatus) {
      alert("Autentique ambos os provedores primeiro.");
      return;
    }
    if (selectedEntries.length === 0) {
      alert("Selecione pelo menos um arquivo ou pasta para migrar.");
      return;
    }
    
    const sourceProvider = direction === 'google_to_microsoft' ? 'google' : 'microsoft';
    const destinationProvider = direction === 'google_to_microsoft' ? 'microsoft' : 'google';

    try {
      const res = await fetch(`${API_BASE}/api/transfer`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          sourceProvider,
          destinationProvider,
          entries: selectedEntries,
          destinationFolderId: destFolderId,
          skipEmptyFiles,
          skipDuplicates,
          processingOrder
        })
      });
      const data = await res.json();
      sessionStorage.setItem('aeroshift.transferId', data.transferId);
      setTransferId(data.transferId);
    } catch (e) {
      alert("Falha ao iniciar a transferência.");
    }
  };

  const handleSwap = () => {
    setDirection(prev => prev === 'google_to_microsoft' ? 'microsoft_to_google' : 'google_to_microsoft');
    setSelectedEntries([]);
    setDestFolderId(null);
  };

  const isSourceGoogle = direction === 'google_to_microsoft';
  const sourceName = isSourceGoogle ? 'Google Drive' : 'OneDrive';
  const destName = isSourceGoogle ? 'OneDrive' : 'Google Drive';
  const sourceReady = isSourceGoogle ? googleStatus : microsoftStatus;
  const destReady = isSourceGoogle ? microsoftStatus : googleStatus;

  return (
    <div className="glass-panel" style={{ maxWidth: '1000px' }}>
      <div className="flex-col" style={{ alignItems: 'center', textAlign: 'center' }}>
        <h1>AeroShift</h1>
        <p>Migre seus arquivos entre provedores de nuvem de forma rápida e segura.</p>
      </div>

      <div className="flex-row space-between" style={{ marginTop: '1rem', alignItems: 'center' }}>
        {isSourceGoogle ? (
          <ProviderCard name="Google Drive" icon={<GoogleDriveIcon />} isConnected={googleStatus} onConnect={() => login('google')} />
        ) : (
          <ProviderCard name="OneDrive" icon={<OneDriveIcon />} isConnected={microsoftStatus} onConnect={() => login('microsoft')} />
        )}
        
        <button 
          onClick={handleSwap} 
          className="btn" 
          style={{ margin: '0 1rem', padding: '0.8rem', borderRadius: '50%', background: 'rgba(255,255,255,0.1)', border: '1px solid var(--card-border)', display: 'flex', alignItems: 'center', justifyContent: 'center' }}
          title="Inverter direção da migração"
        >
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <path d="M16 3h5v5"></path><path d="M21 3l-7 7"></path><path d="M8 21H3v-5"></path><path d="M3 21l7-7"></path>
          </svg>
        </button>

        {isSourceGoogle ? (
          <ProviderCard name="OneDrive" icon={<OneDriveIcon />} isConnected={microsoftStatus} onConnect={() => login('microsoft')} />
        ) : (
          <ProviderCard name="Google Drive" icon={<GoogleDriveIcon />} isConnected={googleStatus} onConnect={() => login('google')} />
        )}
      </div>

      {sourceReady && destReady && !transferId && (
        <div className="flex-row" style={{ marginTop: '1rem', gap: '1rem', flexWrap: 'wrap' }}>
          <div className="flex-col" style={{ flex: '1 1 300px', minWidth: '300px', background: 'rgba(0,0,0,0.2)', padding: '1.5rem', borderRadius: '12px' }}>
            <h3>Origem: {sourceName}</h3>
            <p style={{ fontSize: '0.8rem', color: 'var(--text-secondary)' }}>Selecione os arquivos para transferir</p>
            <FileExplorer 
              provider={isSourceGoogle ? 'google' : 'microsoft'} 
              selectedEntries={selectedEntries} 
              setSelectedEntries={setSelectedEntries} 
            />
          </div>
          
          <div className="flex-col" style={{ flex: '1 1 300px', minWidth: '300px', background: 'rgba(0,0,0,0.2)', padding: '1.5rem', borderRadius: '12px' }}>
            <h3>Destino: {destName}</h3>
            <p style={{ fontSize: '0.8rem', color: 'var(--text-secondary)' }}>Navegue para escolher a pasta destino</p>
            <FileExplorer 
              provider={isSourceGoogle ? 'microsoft' : 'google'} 
              isDestinationPicker={true}
              onFolderChange={setDestFolderId}
            />
          </div>
        </div>
      )}

      <div style={{ marginTop: '1.5rem', textAlign: 'center' }}>
        {!transferId && (
          <>
            <div className="flex-col" style={{ gap: '0.75rem', marginBottom: '1.5rem', background: 'rgba(0,0,0,0.2)', padding: '1rem 1.5rem', borderRadius: '8px', maxWidth: '700px', margin: '0 auto 1.5rem auto' }}>
              <div className="flex-row" style={{ justifyContent: 'center', gap: '2rem', flexWrap: 'wrap' }}>
                <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', cursor: 'pointer', fontSize: '0.9rem' }}>
                  <input 
                    type="checkbox" 
                    checked={skipEmptyFiles} 
                    onChange={(e) => setSkipEmptyFiles(e.target.checked)} 
                    style={{ width: '18px', height: '18px', cursor: 'pointer' }}
                  />
                  Ignorar arquivos vazios (0 bytes)
                </label>
                <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', cursor: 'pointer', fontSize: '0.9rem' }}>
                  <input 
                    type="checkbox" 
                    checked={skipDuplicates} 
                    onChange={(e) => setSkipDuplicates(e.target.checked)} 
                    style={{ width: '18px', height: '18px', cursor: 'pointer' }}
                  />
                  Ignorar arquivos duplicados (mesmo tamanho e hash)
                </label>
              </div>

              <div className="flex-row" style={{ justifyContent: 'center', alignItems: 'center', gap: '0.75rem', fontSize: '0.9rem', paddingTop: '0.5rem', borderTop: '1px solid rgba(255,255,255,0.05)' }}>
                <span style={{ color: 'var(--text-secondary)' }}>Ordem da fila:</span>
                <select 
                  value={processingOrder} 
                  onChange={(e) => setProcessingOrder(e.target.value as any)}
                  style={{ 
                    background: 'rgba(255,255,255,0.1)', 
                    color: 'var(--text-primary)', 
                    border: '1px solid var(--card-border)', 
                    borderRadius: '6px', 
                    padding: '0.35rem 0.8rem',
                    fontSize: '0.85rem',
                    cursor: 'pointer',
                    outline: 'none'
                  }}
                >
                  <option value="SMALLEST_FIRST" style={{ background: '#1e293b', color: '#fff' }}>⚡ Menores primeiro (Rápido)</option>
                  <option value="LARGEST_FIRST" style={{ background: '#1e293b', color: '#fff' }}>🐘 Maiores primeiro</option>
                  <option value="NATURAL" style={{ background: '#1e293b', color: '#fff' }}>📁 Ordem da pasta</option>
                </select>
              </div>
            </div>
            <button 
              className="btn btn-primary" 
              onClick={startTransfer}
              disabled={!googleStatus || !microsoftStatus || selectedEntries.length === 0}
              style={{ width: '100%', maxWidth: '300px' }}
            >
              Iniciar Migração para o {destName}
            </button>
          </>
        )}

        {transferId && !progress && (
          <div className="flex-col" style={{ marginTop: '1rem', background: 'rgba(0,0,0,0.2)', padding: '2rem', borderRadius: '12px', alignItems: 'center' }}>
            <div className="spinner" style={{ marginBottom: '1rem', width: '30px', height: '30px', border: '3px solid rgba(255,255,255,0.1)', borderTopColor: 'var(--accent-color)', borderRadius: '50%', animation: 'spin 1s linear infinite' }}></div>
            <p>Conectando ao motor de transferência, aguarde...</p>
            <style>
              {`@keyframes spin { 0% { transform: rotate(0deg); } 100% { transform: rotate(360deg); } }`}
            </style>
          </div>
        )}

        {progress && (
          <div className="flex-col" style={{ marginTop: '1rem', background: 'rgba(0,0,0,0.2)', padding: '1.5rem', borderRadius: '12px' }}>
            <h3 style={{ margin: 0 }}>Progresso da Migração</h3>
            {(() => {
              const total = progress.totalBytes || 0;
              const done = Math.max(progress.transferredBytes || 0, 0);
              const filesTotal = progress.totalFiles || 0;
              const filesDone = (progress.completedFiles || 0) + (progress.failedFiles || 0);
              const finished = ['COMPLETED', 'COMPLETED_WITH_ERRORS', 'FAILED', 'CANCELLED'].includes(progress.state);
              const pct = finished && progress.state !== 'FAILED' ? 100
                : total > 0 ? Math.min(100, (done / total) * 100)
                : filesTotal > 0 ? (filesDone / filesTotal) * 100 : 0;
              const active = progress.active || [];
              const failures = progress.failures || [];
              const skipped = progress.skipped || [];
              return (
                <>
                  <div className="flex-row space-between">
                    <span className={`status-badge ${progress.state === 'COMPLETED' ? 'connected' : progress.state === 'FAILED' ? 'disconnected' : ''}`}>
                      {progress.state}{!progress.discoveryDone && !finished ? ' · descobrindo arquivos…' : ''}
                    </span>
                    <span>{formatBytes(done)} / {formatBytes(total)} ({pct.toFixed(1)}%)</span>
                  </div>
                  <div className="progress-container">
                    <div className="progress-bar" style={{ width: `${pct}%` }}></div>
                  </div>
                  <div className="flex-row space-between" style={{ fontSize: '0.875rem', color: 'var(--text-secondary)', flexWrap: 'wrap', gap: '0.5rem' }}>
                    <div><strong>Em andamento:</strong> {active.length}</div>
                    <div><strong>Concluídos:</strong> {progress.completedFiles || 0} / {filesTotal}</div>
                    <div><strong>Ignorados:</strong> {skipped.length}</div>
                    <div style={{ color: failures.length > 0 ? 'var(--error)' : 'inherit' }}><strong>Erros:</strong> {failures.length}</div>
                    {progress.bytesPerSecond > 0 && <div><strong>Velocidade:</strong> {formatBytes(progress.bytesPerSecond)}/s</div>}
                  </div>
                  {skipped.length > 0 && (
                    <details style={{ fontSize: '0.8rem' }}>
                      <summary style={{ cursor: 'pointer' }}>Ignorados pela curadoria ({skipped.length})</summary>
                      <ul style={{ maxHeight: '150px', overflowY: 'auto', paddingLeft: '1.2rem', marginTop: '0.5rem' }}>
                        {skipped.map((f: any, i: number) => <li key={i}><code>{f.path}</code> — {f.reason}</li>)}
                      </ul>
                    </details>
                  )}
                  {failures.length > 0 && (
                    <details open style={{ fontSize: '0.8rem', color: 'var(--error)' }}>
                      <summary style={{ cursor: 'pointer' }}>Erros ({failures.length})</summary>
                      <ul style={{ maxHeight: '150px', overflowY: 'auto', paddingLeft: '1.2rem', marginTop: '0.5rem' }}>
                        {failures.map((f: any, i: number) => <li key={i}><code>{f.path}</code> — {f.reason}</li>)}
                      </ul>
                    </details>
                  )}
                </>
              );
            })()}
            
            {(progress.state === 'COMPLETED' || progress.state === 'FAILED' || progress.state === 'COMPLETED_WITH_ERRORS') && (
              <button 
                className="btn btn-primary" 
                style={{ marginTop: '1.5rem', width: '100%', maxWidth: '300px', alignSelf: 'center' }}
                onClick={() => {
                  sessionStorage.removeItem('aeroshift.transferId');
                  setTransferId(null);
                  setProgress(null);
                  setSelectedEntries([]);
                  setDestFolderId(null);
                }}
              >
                Nova Migração
              </button>
            )}
          </div>
        )}
      </div>

      <footer style={{ marginTop: '2.5rem', textAlign: 'center', fontSize: '0.8rem', color: 'var(--text-secondary)', opacity: 0.8, borderTop: '1px solid rgba(255,255,255,0.06)', paddingTop: '1.25rem' }}>
        Desenvolvido por <a href="https://github.com/cesarfarago" target="_blank" rel="noreferrer" style={{ color: 'var(--accent-color)', textDecoration: 'none', fontWeight: 600 }}>Cesar Farago</a> • AeroShift &copy; {new Date().getFullYear()}
      </footer>
    </div>
  );
}

function FileExplorer({ 
  provider, 
  selectedEntries = [], 
  setSelectedEntries, 
  isDestinationPicker = false,
  onFolderChange
}: { 
  provider: string, 
  selectedEntries?: CloudEntry[], 
  setSelectedEntries?: (entries: CloudEntry[]) => void,
  isDestinationPicker?: boolean,
  onFolderChange?: (folderId: string | null) => void
}) {
  const [entries, setEntries] = useState<CloudEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [searchTerm, setSearchTerm] = useState('');
  const [currentFolderId, setCurrentFolderId] = useState<string | null>(null);
  const [folderPath, setFolderPath] = useState<{id: string | null, name: string}[]>([{id: null, name: 'Raiz'}]);

  useEffect(() => {
    loadFolder(currentFolderId);
    if (onFolderChange) {
      onFolderChange(currentFolderId);
    }
  }, [currentFolderId, provider]);

  const loadFolder = async (folderId: string | null) => {
    setLoading(true);
    setSearchTerm('');
    try {
      const url = folderId 
        ? `${API_BASE}/api/files/${provider}?folderId=${encodeURIComponent(folderId)}` 
        : `${API_BASE}/api/files/${provider}`;
      const res = await fetch(url);
      const data = await res.json();
      setEntries(data);
    } catch (e) {
      console.error("Failed to load files", e);
    } finally {
      setLoading(false);
    }
  };

  const toggleSelection = (entry: CloudEntry) => {
    if (isDestinationPicker || !setSelectedEntries) return;
    const isSelected = selectedEntries.some(e => e.id === entry.id);
    if (isSelected) {
      setSelectedEntries(selectedEntries.filter(e => e.id !== entry.id));
    } else {
      setSelectedEntries([...selectedEntries, entry]);
    }
  };

  const navigateTo = (folder: CloudEntry) => {
    setSearchTerm('');
    setCurrentFolderId(folder.id);
    setFolderPath([...folderPath, { id: folder.id, name: folder.name }]);
  };

  const navigateUp = (index: number) => {
    setSearchTerm('');
    const newPath = folderPath.slice(0, index + 1);
    setCurrentFolderId(newPath[newPath.length - 1].id);
    setFolderPath(newPath);
  };

  const filteredEntries = useMemo(() => {
    if (!searchTerm.trim()) return entries;
    const term = searchTerm.toLowerCase();
    return entries.filter(e => e.name.toLowerCase().includes(term));
  }, [entries, searchTerm]);

  return (
    <div className="flex-col" style={{ alignItems: 'stretch' }}>
      <div className="flex-row space-between" style={{ alignItems: 'center', marginBottom: '0.5rem' }}>
        <div className="flex-row" style={{ fontSize: '0.875rem', flexWrap: 'wrap' }}>
          {folderPath.map((crumb, index) => (
            <React.Fragment key={crumb.id || 'root'}>
              {index > 0 && <span style={{ color: 'var(--text-secondary)' }}>/</span>}
              <button 
                className="btn" 
                style={{ padding: '0.2rem 0.5rem', background: 'transparent', border: 'none', color: index === folderPath.length - 1 ? 'var(--text-primary)' : 'var(--accent-color)' }}
                onClick={() => navigateUp(index)}
              >
                {crumb.name}
              </button>
            </React.Fragment>
          ))}
        </div>

        {isDestinationPicker && (
          <button 
            className="btn btn-primary" 
            style={{ padding: '0.2rem 0.75rem', fontSize: '0.8rem' }}
            onClick={async () => {
              const folderName = prompt("Nome da nova pasta:");
              if (!folderName) return;
              
              try {
                const res = await fetch(`${API_BASE}/api/files/${provider}/folder`, {
                  method: 'POST',
                  headers: { 'Content-Type': 'application/json' },
                  body: JSON.stringify({ name: folderName, parentId: currentFolderId })
                });
                if (res.ok) {
                  loadFolder(currentFolderId);
                } else {
                  alert("Erro ao criar pasta");
                }
              } catch (e) {
                console.error(e);
                alert("Erro ao criar pasta");
              }
            }}
          >
            + Nova Pasta
          </button>
        )}
      </div>

      <div style={{ position: 'relative', marginBottom: '0.5rem' }}>
        <input 
          type="text" 
          value={searchTerm} 
          onChange={(e) => setSearchTerm(e.target.value)} 
          placeholder="Pesquisar arquivos e pastas..." 
          style={{
            width: '100%',
            padding: '0.45rem 2rem 0.45rem 2rem',
            background: 'rgba(255,255,255,0.06)',
            border: '1px solid var(--card-border)',
            borderRadius: '6px',
            color: 'var(--text-primary)',
            fontSize: '0.85rem',
            boxSizing: 'border-box',
            outline: 'none'
          }}
        />
        <span style={{ position: 'absolute', left: '0.65rem', top: '50%', transform: 'translateY(-50%)', opacity: 0.6, fontSize: '0.85rem', pointerEvents: 'none' }}>
          🔍
        </span>
        {searchTerm && (
          <button 
            onClick={() => setSearchTerm('')}
            style={{
              position: 'absolute',
              right: '0.65rem',
              top: '50%',
              transform: 'translateY(-50%)',
              background: 'transparent',
              border: 'none',
              color: 'var(--text-secondary)',
              cursor: 'pointer',
              fontSize: '0.85rem',
              padding: 0
            }}
            title="Limpar pesquisa"
          >
            ✕
          </button>
        )}
      </div>

      {!isDestinationPicker && entries.length > 0 && (
        <div 
          className="flex-row space-between" 
          style={{ 
            padding: '0.35rem 0.75rem', 
            fontSize: '0.75rem', 
            color: 'var(--text-secondary)', 
            borderBottom: '1px solid var(--card-border)',
            background: 'rgba(255,255,255,0.02)',
            alignItems: 'center'
          }}
        >
          <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', cursor: 'pointer', userSelect: 'none' }}>
            <input 
              type="checkbox"
              checked={filteredEntries.length > 0 && filteredEntries.every(e => selectedEntries.some(se => se.id === e.id))}
              onChange={() => {
                if (!setSelectedEntries) return;
                const allSelected = filteredEntries.length > 0 && filteredEntries.every(e => selectedEntries.some(se => se.id === e.id));
                if (allSelected) {
                  setSelectedEntries(selectedEntries.filter(se => !filteredEntries.some(e => e.id === se.id)));
                } else {
                  const newSelection = [...selectedEntries];
                  filteredEntries.forEach(e => {
                    if (!newSelection.some(se => se.id === e.id)) {
                      newSelection.push(e);
                    }
                  });
                  setSelectedEntries(newSelection);
                }
              }}
              style={{ width: '15px', height: '15px', cursor: 'pointer', accentColor: 'var(--accent-color)' }}
            />
            <span>
              {filteredEntries.some(e => selectedEntries.some(se => se.id === e.id))
                ? `${filteredEntries.filter(e => selectedEntries.some(se => se.id === e.id)).length} de ${filteredEntries.length} selecionado(s)`
                : 'Selecionar todos'}
            </span>
          </label>
          <span>Tamanho</span>
        </div>
      )}

      <div className="scroll-area" style={{ height: '300px', background: 'rgba(0,0,0,0.3)', borderRadius: '8px', border: '1px solid var(--card-border)' }}>
        {loading ? (
          <div style={{ padding: '1rem', textAlign: 'center', color: 'var(--text-secondary)' }}>Carregando...</div>
        ) : entries.length === 0 ? (
          <div style={{ padding: '1rem', textAlign: 'center', color: 'var(--text-secondary)' }}>Pasta vazia</div>
        ) : filteredEntries.length === 0 ? (
          <div style={{ padding: '1rem', textAlign: 'center', color: 'var(--text-secondary)' }}>
            Nenhum item encontrado para &ldquo;{searchTerm}&rdquo;
          </div>
        ) : (
          <div className="flex-col" style={{ gap: 0 }}>
            {filteredEntries.map(entry => {
              const isSelected = selectedEntries.some(e => e.id === entry.id);
              return (
                <div 
                  key={entry.id} 
                  className="flex-row space-between" 
                  style={{ 
                    padding: '0.65rem 0.85rem', 
                    borderBottom: '1px solid rgba(255,255,255,0.05)',
                    background: isSelected && !isDestinationPicker ? 'rgba(59, 130, 246, 0.2)' : 'transparent',
                    cursor: entry.isFolder || (!isDestinationPicker) ? 'pointer' : 'default',
                    opacity: isDestinationPicker && !entry.isFolder ? 0.5 : 1,
                    alignItems: 'center'
                  }}
                  onClick={() => entry.isFolder ? navigateTo(entry) : toggleSelection(entry)}
                >
                  <div className="flex-row" style={{ flex: 1, alignItems: 'center', minWidth: 0, overflow: 'hidden' }}>
                    {!isDestinationPicker && (
                      <input 
                        type="checkbox"
                        checked={isSelected}
                        onChange={(e) => {
                          e.stopPropagation();
                          toggleSelection(entry);
                        }}
                        onClick={(e) => e.stopPropagation()}
                        style={{
                          width: '16px',
                          height: '16px',
                          cursor: 'pointer',
                          marginRight: '0.75rem',
                          accentColor: 'var(--accent-color)',
                          flexShrink: 0
                        }}
                      />
                    )}
                    <div style={{ color: entry.isFolder ? '#FBBF24' : 'var(--text-secondary)', display: 'flex', marginRight: '0.5rem', flexShrink: 0 }}>
                      {entry.isFolder ? <FolderIcon /> : <FileIcon />}
                    </div>
                    <span style={{ 
                      fontWeight: entry.isFolder ? 600 : 400,
                      whiteSpace: 'nowrap',
                      overflow: 'hidden',
                      textOverflow: 'ellipsis'
                    }}>
                      {entry.name}
                    </span>
                  </div>
                  
                  <div className="flex-row" style={{ alignItems: 'center', flexShrink: 0, marginLeft: '0.75rem' }}>
                    {!entry.isFolder ? (
                      <span style={{ color: 'var(--text-secondary)', fontSize: '0.8rem' }}>{formatBytes(entry.sizeBytes)}</span>
                    ) : (
                      <span style={{ color: 'var(--text-secondary)', fontSize: '0.75rem', opacity: 0.6 }}>Pasta</span>
                    )}
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
}

function ProviderCard({ name, icon, isConnected, onConnect }: { name: string, icon: React.ReactNode, isConnected: boolean, onConnect: () => void }) {
  return (
    <div className="flex-col" style={{ alignItems: 'center', background: 'rgba(255,255,255,0.05)', padding: '1.5rem', borderRadius: '16px', flex: 1, border: '1px solid var(--card-border)' }}>
      {icon}
      <h2>{name}</h2>
      {isConnected ? (
        <span className="status-badge connected">Conectado</span>
      ) : (
        <button className="btn btn-primary" onClick={onConnect}>Conectar</button>
      )}
    </div>
  );
}

function formatBytes(bytes: number, decimals = 2) {
    if (!+bytes) return '0 B'
    const k = 1024
    const dm = decimals < 0 ? 0 : decimals
    const sizes = ['B', 'KB', 'MB', 'GB', 'TB', 'PB', 'EB', 'ZB', 'YB']
    const i = Math.floor(Math.log(bytes) / Math.log(k))
    return `${parseFloat((bytes / Math.pow(k, i)).toFixed(dm))} ${sizes[i]}`
}

export default App;
