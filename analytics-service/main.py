from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import StreamingResponse

from analysis import analyze_dataframe, read_csv
from anomalies import detect_anomalies
from cleaning import actions_from_json, apply_cleaning, build_suggestions
from insights import detect_trends
from performers import detect_performers
from period_comparison import compare_periods
from project_insights import compare_siblings, relational_insights
from duckdb_query import execute_query
from schemas import (
    AnalysisResponse,
    AnomaliesResponse,
    CleaningSuggestionsResponse,
    PartialAnalysis,
    PeriodComparisonResponse,
    PerformersResponse,
    QueryResultResponse,
    RelationalInsightResponse,
    SiblingComparisonResponse,
    TrendInsightsResponse,
)
from typing import Optional

import io
import json
import pydantic

app = FastAPI(
    title="Analytics Service",
    description="Data analysis microservice for the AI-powered data analytics platform",
    version="0.3.0",
)

# Uploads are capped in-memory to avoid unbounded memory consumption on the
# server when a client streams a very large file.
MAX_UPLOAD_BYTES = 50 * 1024 * 1024  # 50 MB


def _read_upload(upload: UploadFile, field_name: str) -> bytes:
    """Reads an uploaded file, rejecting over-sized payloads and empty files."""
    if not upload.filename or not upload.filename.lower().endswith(".csv"):
        raise HTTPException(status_code=400, detail="Only .csv files are accepted.")
    content = upload.file.read(MAX_UPLOAD_BYTES + 1)
    if len(content) > MAX_UPLOAD_BYTES:
        raise HTTPException(
            status_code=413,
            detail=f"The uploaded {field_name} exceeds the {MAX_UPLOAD_BYTES // (1024 * 1024)} MB size limit.",
        )
    if not content:
        raise HTTPException(status_code=400, detail=f"The uploaded {field_name} is empty.")
    return content


def _sanitize_detail(message: str) -> str:
    """Returns a generic message so internal paths/SQL are never leaked to clients."""
    return "An unexpected error occurred while processing the request."


@app.get("/health")
def health_check():
    return {"status": "ok", "service": "analytics-service"}


@app.post(
    "/analyze",
    response_model=AnalysisResponse,
    responses={
        400: {"description": "Invalid file"},
        422: {"description": "Unparseable CSV"},
        500: {"description": "Unexpected error"},
    },
)
async def analyze(file: UploadFile = File(...)) -> AnalysisResponse:
    """Analyzes an uploaded CSV file and returns structured statistics."""
    content = _read_upload(file, "file")

    frame = read_csv(content)

    try:
        return analyze_dataframe(frame)
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(status_code=500, detail=_sanitize_detail(str(exc))) from exc


@app.post(
    "/insights/period-comparison",
    response_model=PeriodComparisonResponse,
    responses={
        400: {"description": "Invalid file or parameters"},
        422: {"description": "Unparseable CSV"},
        500: {"description": "Unexpected error"},
    },
)
async def insights_period_comparison(
    file: UploadFile = File(...),
    periodType: Optional[str] = None,
    customCurrentStart: Optional[str] = None,
    customCurrentEnd: Optional[str] = None,
    customPreviousStart: Optional[str] = None,
    customPreviousEnd: Optional[str] = None,
) -> PeriodComparisonResponse:
    """Compares the two most recent complete periods (or custom ranges)."""
    content = _read_upload(file, "file")

    frame = read_csv(content)

    try:
        analysis = analyze_dataframe(frame)
        return compare_periods(
            frame, analysis, periodType,
            customCurrentStart, customCurrentEnd,
            customPreviousStart, customPreviousEnd,
        )
    except HTTPException:
        raise
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except Exception as exc:
        raise HTTPException(
            status_code=500, detail=_sanitize_detail(str(exc))
        ) from exc


@app.post(
    "/insights/anomalies",
    response_model=AnomaliesResponse,
    responses={
        400: {"description": "Invalid file"},
        422: {"description": "Unparseable CSV"},
        500: {"description": "Unexpected error"},
    },
)
async def insights_anomalies(file: UploadFile = File(...)) -> AnomaliesResponse:
    """Flags whole time periods that deviate unusually from the norm."""
    content = _read_upload(file, "file")

    frame = read_csv(content)

    try:
        analysis = analyze_dataframe(frame)
        return detect_anomalies(frame, analysis)
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(
            status_code=500, detail=_sanitize_detail(str(exc))
        ) from exc


@app.post(
    "/insights/top-bottom-performers",
    response_model=PerformersResponse,
    responses={
        400: {"description": "Invalid file"},
        422: {"description": "Unparseable CSV"},
        500: {"description": "Unexpected error"},
    },
)
async def insights_performers(file: UploadFile = File(...)) -> PerformersResponse:
    """Ranks categories by a numeric metric and surfaces best/worst performers."""
    content = _read_upload(file, "file")

    frame = read_csv(content)

    try:
        analysis = analyze_dataframe(frame)
        return detect_performers(frame, analysis)
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(
            status_code=500, detail=_sanitize_detail(str(exc))
        ) from exc


@app.post(
    "/insights/sibling-comparison",
    response_model=SiblingComparisonResponse,
    responses={
        400: {"description": "Invalid files or analysis payloads"},
        422: {"description": "Unparseable CSV"},
        500: {"description": "Unexpected error"},
    },
)
async def insights_sibling_comparison(
    datasetA: UploadFile = File(...),
    datasetB: UploadFile = File(...),
    datasetALabel: Optional[str] = Form(default=None),
    datasetBLabel: Optional[str] = Form(default=None),
    analysisA: Optional[str] = Form(default=None),
    analysisB: Optional[str] = Form(default=None),
) -> SiblingComparisonResponse:
    """Compares two schema-twin datasets like two periods of one report.

    The optional analysis payloads carry already-computed semantic roles;
    when absent they are re-derived from the files.
    """
    frame_a, analysis_a = await _frame_and_analysis(datasetA, analysisA, "datasetA")
    frame_b, analysis_b = await _frame_and_analysis(datasetB, analysisB, "datasetB")

    try:
        return compare_siblings(
            frame_a, frame_b,
            datasetALabel or _stem(datasetA.filename),
            datasetBLabel or _stem(datasetB.filename),
            analysis_a, analysis_b,
        )
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(
            status_code=500,
            detail=_sanitize_detail(str(exc)),
        ) from exc


@app.post(
    "/insights/relational",
    response_model=RelationalInsightResponse,
    responses={
        400: {"description": "Invalid files or columns"},
        422: {"description": "Unparseable CSV"},
        500: {"description": "Unexpected error"},
    },
)
async def insights_relational(
    parentFile: UploadFile = File(...),
    childFile: UploadFile = File(...),
    parentColumn: str = Form(...),
    childColumn: str = Form(...),
    parentLabel: Optional[str] = Form(default=None),
    childLabel: Optional[str] = Form(default=None),
    matchPercentage: Optional[float] = Form(default=None),
    analysisParent: Optional[str] = Form(default=None),
    analysisChild: Optional[str] = Form(default=None),
) -> RelationalInsightResponse:
    """Referential completeness + join aggregate for a confirmed FK link."""
    frame_parent, analysis_parent = await _frame_and_analysis(
        parentFile, analysisParent, "parentFile")
    frame_child, analysis_child = await _frame_and_analysis(
        childFile, analysisChild, "childFile")

    if parentColumn not in frame_parent.columns:
        raise HTTPException(
            status_code=400,
            detail=f"Column '{parentColumn}' was not found in the parent file.",
        )
    if childColumn not in frame_child.columns:
        raise HTTPException(
            status_code=400,
            detail=f"Column '{childColumn}' was not found in the child file.",
        )

    try:
        return relational_insights(
            frame_parent, frame_child, parentColumn, childColumn,
            parentLabel or _stem(parentFile.filename),
            childLabel or _stem(childFile.filename),
analysis_child, matchPercentage,
        )
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(
            status_code=500,
            detail=_sanitize_detail(str(exc)),
        ) from exc


async def _frame_and_analysis(
    upload: UploadFile, analysis_json: Optional[str], field_name: str
):
    """Reads an uploaded CSV; reuses the supplied partial analysis when valid."""
    content = _read_upload(upload, field_name)

    frame = read_csv(content)
    analysis: PartialAnalysis | None = None
    if analysis_json:
        try:
            analysis = PartialAnalysis.model_validate_json(analysis_json)
            if not analysis.columns:
                analysis = None
        except pydantic.ValidationError:
            analysis = None
    if analysis is None:
        full = analyze_dataframe(frame)
        analysis = PartialAnalysis(columns=full.columns)
    return frame, analysis


def _stem(filename: str | None) -> str:
    if not filename:
        return "Dataset"
    name = filename.rsplit("/", 1)[-1].rsplit("\\", 1)[-1]
    return name[:-4] if name.lower().endswith(".csv") else name


@app.post(
    "/clean/suggestions",
    response_model=CleaningSuggestionsResponse,
    responses={
        400: {"description": "Invalid file"},
        422: {"description": "Unparseable CSV"},
    },
)
async def clean_suggestions(file: UploadFile = File(...)) -> CleaningSuggestionsResponse:
    """Analyzes the CSV and returns cleaning suggestions (advisory only)."""
    content = _read_upload(file, "file")

    frame = read_csv(content)

    try:
        return build_suggestions(frame)
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(
            status_code=500, detail=_sanitize_detail(str(exc))
        ) from exc


@app.post(
    "/insights/trends",
    response_model=TrendInsightsResponse,
    responses={
        400: {"description": "Invalid file"},
        422: {"description": "Unparseable CSV"},
        500: {"description": "Unexpected error"},
    },
)
async def insights_trends(file: UploadFile = File(...)) -> TrendInsightsResponse:
    """Detects up/down/stable trends for numeric columns over temporal columns."""
    content = _read_upload(file, "file")

    frame = read_csv(content)

    try:
        analysis = analyze_dataframe(frame)
        return detect_trends(frame, analysis)
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(
            status_code=500, detail=_sanitize_detail(str(exc))
        ) from exc


@app.post(
    "/clean/apply",
    responses={
        400: {"description": "Invalid actions or unparseable CSV"},
        422: {"description": "Unparseable CSV"},
    },
)
async def clean_apply(
    file: UploadFile = File(...), actions: str = Form(...)
) -> StreamingResponse:
    """Applies cleaning actions and streams back the cleaned CSV.

    The cleaning summary is returned in the `X-Cleaning-Summary` header.
    """
    content = _read_upload(file, "file")

    selected = actions_from_json(actions)
    frame = read_csv(content)

    try:
        cleaned, summary = apply_cleaning(frame, selected)
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(status_code=500, detail=_sanitize_detail(str(exc))) from exc

    buffer = io.StringIO()
    cleaned.to_csv(buffer, index=False)
    payload = buffer.getvalue().encode("utf-8")

    return StreamingResponse(
        io.BytesIO(payload),
        media_type="text/csv",
        headers={
            "Content-Disposition": 'attachment; filename="cleaned.csv"',
            "X-Cleaning-Summary": json.dumps(
                {
                    "rowsBefore": summary.rowsBefore,
                    "rowsAfter": summary.rowsAfter,
                    "rowsRemoved": summary.rowsRemoved,
                    "valuesFilled": summary.valuesFilled,
                }
            ),
        },
    )


@app.post(
    "/query/execute",
    response_model=QueryResultResponse,
    responses={
        400: {"description": "Missing SQL or dataset files"},
        500: {"description": "Unexpected error"},
    },
)
async def query_execute(
    sql: str = Form(...),
    tablesJson: str = Form(...),
    files: list[UploadFile] = File(...),
) -> QueryResultResponse:
    """Runs a read-only SELECT over the provided CSV datasets in DuckDB.

    `sql` is the LLM-proposed query; `tablesJson` maps sanitized table names
    to original filenames so uploaded parts can be matched. Semantic problems
    (forbidden keywords, unknown tables, SQL errors, timeouts) are reported
    as success=False instead of HTTP errors so the caller can relay them to
    the assistant conversation.
    """
    try:
        mapping = json.loads(tablesJson)
    except json.JSONDecodeError as exc:
        raise HTTPException(status_code=400, detail="tablesJson is not valid JSON.") from exc
    if not isinstance(mapping, dict) or not mapping:
        raise HTTPException(status_code=400, detail="tablesJson must map table names to filenames.")

    contents: dict[str, bytes] = {}
    for upload in files:
        content = upload.file.read(MAX_UPLOAD_BYTES + 1)
        if len(content) > MAX_UPLOAD_BYTES:
            raise HTTPException(
                status_code=413,
                detail=f"The uploaded file '{upload.filename}' exceeds the "
                       f"{MAX_UPLOAD_BYTES // (1024 * 1024)} MB size limit.",
            )
        if upload.filename:
            contents[upload.filename] = content

    tables: dict[str, bytes] = {}
    for table_name, filename in mapping.items():
        if filename not in contents:
            raise HTTPException(
                status_code=400,
                detail=f"No file part received for dataset '{filename}'.")
        tables[table_name] = contents[filename]

    return execute_query(sql, tables)
