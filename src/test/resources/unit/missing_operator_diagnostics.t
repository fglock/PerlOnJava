use Test::More;

sub compile_diagnostics {
    my ($source) = @_;
    my $warning = '';
    local $SIG{__WARN__} = sub { $warning .= shift };
    eval $source;
    return $warning . $@;
}

like(compile_diagnostics(q{myfunc 1,2,3}),
     qr/\ANumber found where operator expected \(Do you need to predeclare "myfunc"\?\) at \(eval \d+\) line 1, near "myfunc 1"\nsyntax error at \(eval \d+\) line 1, near "myfunc 1"\n(?:Execution of \(eval \d+\) aborted due to compilation errors\.\n)?\z/,
     'an adjacent number after a bareword reports a missing operator');

like(compile_diagnostics(q!0${!),
     qr/\AScalar found where operator expected \(Missing operator before "\$\{"\?\) at \(eval \d+\) line 1, near "0\$\{"\nsyntax error at \(eval \d+\) line 1, near "0\$"\n(?:Execution of \(eval \d+\) aborted due to compilation errors\.\n)?\z/,
     'an adjacent scalar dereference reports a missing operator');

like(compile_diagnostics(q!0$#{!),
     qr/\AArray length found where operator expected \(Missing operator before "\$#\{"\?\) at \(eval \d+\) line 1, near "0\$#\{"\nsyntax error at \(eval \d+\) line 1, near "0\$#"\n(?:Execution of \(eval \d+\) aborted due to compilation errors\.\n)?\z/,
     'an adjacent array length dereference reports a missing operator');

like(compile_diagnostics(q{0@foo}),
     qr/\AArray found where operator expected \(Missing operator before "\@foo"\?\) at \(eval \d+\) line 1, near "0\@foo"\nsyntax error at \(eval \d+\) line 1, near "0\@foo\n"\n(?:Execution of \(eval \d+\) aborted due to compilation errors\.\n)?\z/,
     'an adjacent array reports a missing operator');

done_testing;
