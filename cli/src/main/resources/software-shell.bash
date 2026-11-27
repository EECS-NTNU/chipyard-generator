if [[ -r ~/.bashrc ]]; then
    source ~/.bashrc
fi

if [[ $(type -t conda) != function ]]; then
    if ! presolve_conda_base=$("${CONDA_EXE:-conda}" info --base) ||
        [[ ! -r $presolve_conda_base/etc/profile.d/conda.sh ]] ||
        ! source "$presolve_conda_base/etc/profile.d/conda.sh"; then
        printf 'presolve: Could not initialize Conda. Make conda available on PATH or set CONDA_EXE.\n' >&2
        exit 1
    fi
    unset presolve_conda_base
fi

if ! conda activate "$PRESOLVE_SOFTWARE_PREFIX"; then
    printf 'presolve: Could not activate the Chipyard Conda environment at %s.\n' "$PRESOLVE_SOFTWARE_PREFIX" >&2
    exit 1
fi

export RISCV="$CONDA_PREFIX/riscv-tools"
if [[ $PATH != "$RISCV/bin" && $PATH != "$RISCV/bin:"* ]]; then
    export PATH="$RISCV/bin:$PATH"
fi

presolve_firemarshal="${PRESOLVE_SOFTWARE_PREFIX%/*}/software/firemarshal"
unset PRESOLVE_SOFTWARE_PREFIX FIREMARSHAL_DIR MARSHAL_BOARD_DIR
if [[ -x $presolve_firemarshal/marshal && -d $presolve_firemarshal/boards/chipyard ]]; then
    export FIREMARSHAL_DIR="$presolve_firemarshal"
    export MARSHAL_BOARD_DIR="$FIREMARSHAL_DIR/boards/chipyard"
    export PATH="$FIREMARSHAL_DIR:$PATH"
fi
unset presolve_firemarshal
