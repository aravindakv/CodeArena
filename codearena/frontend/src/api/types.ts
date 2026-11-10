// Response shapes of the /api/v1 endpoints (kept by hand; generate them from OpenAPI once the API settles).

export type Difficulty = "EASY" | "MEDIUM" | "HARD";
export type Role = "USER" | "ADMIN";

export interface ProblemSummary {
  number: number;
  slug: string;
  title: string;
  difficulty: Difficulty;
  tags: string[];
  acceptance: number;
}

export interface ProblemPage {
  items: ProblemSummary[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

export interface Example {
  input: string;
  output: string;
  explanation?: string;
}

export interface ProblemDetail {
  number: number;
  slug: string;
  title: string;
  difficulty: Difficulty;
  tags: string[];
  statement: string;
  constraints: string[];
  examples: Example[];
  hints: string[];
  limits: { timeMs: number; memoryMb: number };
  acceptance: number;
  languages: string[];
}

export interface Language {
  id: string;
  label: string;
  monacoId: string;
}

export type Verdict =
  | "PENDING" | "ACCEPTED" | "WRONG_ANSWER" | "TIME_LIMIT_EXCEEDED" | "MEMORY_LIMIT_EXCEEDED"
  | "RUNTIME_ERROR" | "COMPILATION_ERROR" | "OUTPUT_LIMIT_EXCEEDED" | "SYSTEM_ERROR";

export interface Submission {
  id: string;
  slug: string;
  lang: string;
  mode: "RUN" | "SUBMIT";
  contestId?: string;
  status: Verdict;
  createdAt: string;
  runtimeMs: number;
  memoryKb: number;
  passed: number;
  total: number;
  message?: string;
  failedInput?: string;
  expected?: string;
  actual?: string;
  stdout?: string;
  customOutput?: string;
}

export interface Contest {
  id: string;
  title: string;
  description?: string;
  startAt: string;
  endAt: string;
  phase: "UPCOMING" | "LIVE" | "ENDED";
  problems: string[];
  frozen: boolean;
  finalized: boolean;
}

export interface LeaderboardRow {
  rank: number;
  userId: string;
  name: string;
  solved: number;
  penalty: number;
  problems: Record<string, { solvedAtMin: number; wrong: number }>;
}

export interface Leaderboard {
  contestId: string;
  generatedAt?: string;
  frozen?: boolean;
  problems?: string[];
  participants: number;
  rows: LeaderboardRow[];
}

export interface Stats {
  submissions: number;
  accepted: number;
  solvedTotal: number;
  solvedByDifficulty: Record<string, number>;
  byLanguage: Record<string, number>;
  byTag: Record<string, number>;
  currentStreak: number;
  longestStreak: number;
  heatmap: Record<string, number>;
  recentSolved: { slug: string; at: string; difficulty: string }[];
}

export interface Me {
  id: string;
  email: string;
  displayName: string;
  roles: Role[];
  createdAt: string;
}