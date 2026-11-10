#!/bin/bash
source /runner/common.sh
# Syntax errors surface as a compile error, like in compiled languages.
if ! python3 -I -c 'import ast,sys; ast.parse(open("/work/solution.py").read())' 2> /out/compile.err; then
  compile_failed 1
fi
run_program python3 -I /work/harness.py