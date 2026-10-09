"""API server. Run with:  python -m callagent  [--host 0.0.0.0] [--port 8100]"""
import argparse

import uvicorn

from .api import create_app

parser = argparse.ArgumentParser()
parser.add_argument("--host", default="0.0.0.0")
parser.add_argument("--port", type=int, default=8100)
args = parser.parse_args()

uvicorn.run(create_app(), host=args.host, port=args.port)
