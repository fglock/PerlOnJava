use strict;
use warnings;
use Test::More tests => 8;

for my $case (
    [ '[:alpha:]', 'a', qr/POSIX syntax \[: :] belongs inside character classes in regex/,
      'valid POSIX spelling' ],
    [ '[:zog:]', 'z', qr/POSIX syntax \[: :] belongs inside character classes \(but this one isn't fully valid\) in regex/,
      'invalid POSIX spelling' ],
    [ '[.zog.]', 'z', qr/POSIX syntax \[\. \.\] belongs inside character classes \(but this one isn't implemented\) in regex/,
      'unimplemented collating spelling' ],
    [ '[=zog=]', 'z', qr/POSIX syntax \[= =\] belongs inside character classes \(but this one isn't implemented\) in regex/,
      'unimplemented equivalence spelling' ],
) {
    my ($source, $subject, $warning, $label) = @$case;
    my @warnings;
    my $compiled;
    {
        local $SIG{__WARN__} = sub { push @warnings, @_ };
        $compiled = eval "qr/$source/";
    }
    like(join('', @warnings), $warning, "$label warns at construction");
    ok(defined($compiled) && $subject =~ $compiled,
       "$label retains ordinary character-class semantics");
}
