CREATE DATABASE IF NOT EXISTS agent DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE agent;

CREATE TABLE IF NOT EXISTS users (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  username VARCHAR(100) NOT NULL UNIQUE,
  password_hash VARCHAR(255) NOT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL
);

CREATE TABLE IF NOT EXISTS chat_sessions (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  session_id VARCHAR(64) NOT NULL UNIQUE,
  session_name VARCHAR(255) NOT NULL,
  user_id BIGINT,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL
);

CREATE TABLE IF NOT EXISTS chat_messages (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  message_id VARCHAR(64) NOT NULL UNIQUE,
  session_id VARCHAR(64) NOT NULL,
  user_question TEXT,
  model_answer MEDIUMTEXT,
  think MEDIUMTEXT,
  documents MEDIUMTEXT,
  recommended_questions MEDIUMTEXT,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL
);

CREATE TABLE IF NOT EXISTS repository_files (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  file_id VARCHAR(64) NOT NULL UNIQUE,
  file_name VARCHAR(255) NOT NULL UNIQUE,
  storage_path VARCHAR(500) NOT NULL,
  file_size BIGINT NOT NULL,
  user_id VARCHAR(64),
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL
);

CREATE TABLE IF NOT EXISTS session_documents (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  session_id VARCHAR(64) NOT NULL,
  document_name VARCHAR(255) NOT NULL,
  document_type VARCHAR(50),
  file_size BIGINT NOT NULL DEFAULT 0,
  upload_time DATETIME NOT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL
);

CREATE TABLE IF NOT EXISTS session_document_chunks (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  document_id BIGINT NOT NULL,
  session_id VARCHAR(64) NOT NULL,
  chunk_index INT NOT NULL,
  start_offset INT NOT NULL,
  end_offset INT NOT NULL,
  content MEDIUMTEXT NOT NULL,
  embedding MEDIUMTEXT NOT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  INDEX idx_session_document_chunks_session_id (session_id),
  INDEX idx_session_document_chunks_document_id (document_id)
);
