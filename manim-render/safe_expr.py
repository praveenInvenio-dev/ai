"""
Safe formula evaluation for the Manim templates (numpy only, no Manim import, so the server can validate input cheaply).

A formula is parsed into a Python AST and every node is checked against a small whitelist before it is compiled:
numbers, the names x / pi / e, + - * / ** and a few one-argument maths functions. Anything else is rejected, so a model or a
user can never run code through a formula.
"""
import ast
import math
import re

import numpy as np

_FUNCS = {"sin": np.sin, "cos": np.cos, "tan": np.tan, "exp": np.exp, "log": np.log, "ln": np.log, "sqrt": np.sqrt, "abs": np.abs,
          "asin": np.arcsin, "acos": np.arccos, "atan": np.arctan, "sinh": np.sinh, "cosh": np.cosh, "tanh": np.tanh}
_CONSTS = {"pi": math.pi, "e": math.e}
_BIN = (ast.Add, ast.Sub, ast.Mult, ast.Div, ast.Pow)


def _check(node, depth=0):
    if depth > 12:
        raise ValueError("expression too deep")
    if isinstance(node, ast.Expression):
        return _check(node.body, depth + 1)
    if isinstance(node, ast.BinOp) and isinstance(node.op, _BIN):
        if isinstance(node.op, ast.Pow) and isinstance(node.right, ast.Constant) and abs(float(node.right.value)) > 12:
            raise ValueError("exponent too large")
        _check(node.left, depth + 1)
        return _check(node.right, depth + 1)
    if isinstance(node, ast.UnaryOp) and isinstance(node.op, (ast.UAdd, ast.USub)):
        return _check(node.operand, depth + 1)
    if isinstance(node, ast.Call):
        if not (isinstance(node.func, ast.Name) and node.func.id in _FUNCS and len(node.args) == 1 and not node.keywords):
            raise ValueError("only sin, cos, tan, exp, log, sqrt, abs ... of one value are allowed")
        return _check(node.args[0], depth + 1)
    if isinstance(node, ast.Name):
        if node.id in ("x",) or node.id in _CONSTS:
            return
        raise ValueError("unknown name '%s' (use x, pi, e)" % node.id)
    if isinstance(node, ast.Constant) and isinstance(node.value, (int, float)) and not isinstance(node.value, bool):
        return
    raise ValueError("not a plain maths expression")


def safe_function(expr):
    """expr in x, e.g. 'x^2 - 4x + 3' or 'sin(x)/x'. Returns f(np.ndarray) -> np.ndarray. Raises ValueError for anything else."""
    s = (expr or "").strip()
    if not s or len(s) > 120:
        raise ValueError("expression is empty or too long")
    s = s.replace("^", "**").replace("\u00d7", "*").replace("\u2212", "-").replace("\u03c0", "pi").replace("\u00b7", "*")
    s = re.sub(r"(?<=\d)\s*(?=[a-zA-Z(])", "*", s)          # 2x -> 2*x, 3(x+1) -> 3*(x+1)
    s = re.sub(r"(?<=\))\s*(?=[\da-zA-Z(])", "*", s)        # )( -> )*(
    s = re.sub(r"(?<![a-zA-Z])x\s*(?=\()", "x*", s)         # x(x+1) -> x*(x+1)
    s = re.sub(r"(?<=\bx)\s*(?=x\b)", "*", s)               # xx -> x*x
    tree = ast.parse(s, mode="eval")
    _check(tree)
    code = compile(tree, "<expr>", "eval")

    def f(x):
        with np.errstate(all="ignore"):
            y = eval(code, {"__builtins__": {}}, {**_FUNCS, **_CONSTS, "x": np.asarray(x, dtype=float)})  # noqa: S307 - validated AST only
        return np.broadcast_to(np.asarray(y, dtype=float), np.shape(x)).copy()
    return f


