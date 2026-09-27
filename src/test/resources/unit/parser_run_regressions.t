use strict;
use warnings;
use Test::More;
use File::Temp qw(tempdir);
use File::Spec;

for my $name (qw(00 001 01 09324)) {
    my $ok = eval "print \${$name}; 1";
    ok(!$ok, "braced numeric variable \${$name} is rejected");
    like($@,
         qr/Numeric variables with more than one digit may not start with '0'/,
         "braced numeric variable \${$name} has Perl's diagnostic");
}

{
    my $warning = '';
    my $ok;
    {
        local $SIG{__WARN__} = sub { $warning .= shift };
        $ok = eval q{-C-};
    }
    ok(!defined $ok, 'ambiguous -C- fails compilation');
    like($warning, qr/Use of "-C-" without parentheses is ambiguous/,
         'ambiguous -C- emits its warning');
    like($@, qr/syntax error .* at EOF/s,
         'ambiguous -C- reports EOF syntax error');
}

{
    my $dir = tempdir(CLEANUP => 1);
    my $path = File::Spec->catfile($dir, 'leading_blank_lines.pm');
    open my $fh, '>', $path or die $!;
    print $fh +( "\n" x 50_000 ), "1;\n";
    close $fh or die $!;
    is(require $path, 1,
       'print FILEHANDLE +(EXPR) preserves a module with many leading blank lines');
}

done_testing();
