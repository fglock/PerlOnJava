use strict;
use warnings;
no strict 'refs';

print "1..2\n";

sub report {
    my ($ok, $name) = @_;
    print($ok ? "ok" : "not ok", " - $name\n");
}

sub run_case {
    my $E = 'E';
    my $e = 'ee';

    {
        package Local::RuntimeRegexNoTestMore;
        use overload
            '""' => sub { ${$_[0]} },
            '.' => sub {
                my ($x, $y) = @_[ $_[2] ? (1, 0) : (0, 1) ];
                my ($xx, $yy) = ("$x", "$y");
                lc("$xx=$yy");
            };
    }

    my $r = qr/(??{$E})/;
    bless $r, 'Local::RuntimeRegexNoTestMore';
    use re 'eval';
    report('=ee' =~ qr/^$r$/, 'runtime source sees containing lexical');
    report('aa=ee' =~ qr/^(??{'aa'})$r$/,
        'literal callback and overloaded source compose');
}

run_case();
