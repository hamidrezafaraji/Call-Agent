"""API server. Run with:  python -m callagent  [--host 0.0.0.0] [--port 8100]
Admin page: http://localhost:8100/admin"""
import argparse

import uvicorn

from .api import create_app
from .config import load_settings

settings = load_settings()
parser = argparse.ArgumentParser()
parser.add_argument("--host", default="0.0.0.0")
parser.add_argument("--port", type=int, default=settings.port)
args = parser.parse_args()

uvicorn.run(create_app(settings), host=args.host, port=args.port)
