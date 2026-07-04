"""Structured output schema for codebase knowledge extraction.

These Pydantic models define the exact JSON shape we ask the LLM to
produce (and validate against) at both the per-file and project level.
Using a schema-first approach keeps the LLM's output consistent and
machine-readable, per the assignment's "Best Practices" requirement.
"""
from __future__ import annotations

from typing import List, Optional

from pydantic import BaseModel, Field


class MethodInfo(BaseModel):
    name: str = Field(description="Method name")
    signature: str = Field(description="Full method signature, e.g. 'public List<Film> findAll(int page)'")
    description: str = Field(description="One or two sentence description of what the method does")


class FileAnalysis(BaseModel):
    file_path: str = Field(description="Path of the file relative to the repository root")
    package: Optional[str] = Field(default=None, description="Java package name, if applicable")
    class_names: List[str] = Field(default_factory=list, description="Top-level class/interface/enum names declared in the file")
    responsibility: str = Field(description="One paragraph summary of this file's purpose/role in the system")
    key_methods: List[MethodInfo] = Field(default_factory=list, description="The most important methods in the file")
    complexity: str = Field(description="One of: Low, Medium, High")
    complexity_notes: str = Field(description="Brief justification for the complexity rating, and any noteworthy patterns, risks, or design choices")


class ChunkAnalysis(BaseModel):
    """Raw result returned by the LLM for a single chunk (one or more files)."""
    files: List[FileAnalysis] = Field(default_factory=list)


class ProjectOverview(BaseModel):
    purpose: str = Field(description="High-level description of what the project does and why it exists")
    architecture_summary: str = Field(description="Summary of the overall architecture / layering / key design patterns observed")
    key_modules: List[str] = Field(default_factory=list, description="Notable modules/packages and their responsibilities, in short form")
    notable_technologies: List[str] = Field(default_factory=list, description="Key frameworks/libraries the project relies on")


class CodebaseKnowledge(BaseModel):
    """Final structured deliverable: project-level overview + per-file analysis."""
    project_overview: ProjectOverview
    files: List[FileAnalysis]
    metadata: dict = Field(default_factory=dict, description="Run metadata: model used, chunk count, token settings, timestamp, etc.")
