#!/usr/bin/perl
use strict;
use warnings;
use Test::More tests => 3;

# Keep wide test output off the real STDERR so TAP stays clean.

sub capture_warn_stderr_print {
    my ($code) = @_;
    open my $cap, '>', \my $sink or die $!;
    local *STDERR = $cap;
    my $warn = '';
    local $SIG{__WARN__} = sub { $warn .= $_[0] };
    $code->();
    return $warn;
}

like(
    capture_warn_stderr_print(
        sub {
            print STDERR "\x{540d}";
        }
    ),
    qr/Wide character/,
    'wide character warns on STDERR without encoding layer'
);

require open;
'open'->import( ':std', 'utf8' );

ok(
    scalar( grep { $_ eq 'utf8' } PerlIO::get_layers( \*STDOUT ) ),
    'STDOUT has utf8 layer after use open :std => utf8'
);
ok(
    scalar( grep { $_ eq 'utf8' } PerlIO::get_layers( \*STDERR ) ),
    'STDERR has utf8 layer after use open :std => utf8'
);
