use strict;
use warnings;
no warnings 'experimental::regex_sets';
use Test::More tests => 6;

for my $source (
    q{qr/[[:w:]]/},
    q{qr/[[:FOO:]]/},
    q{qr/(?[[:w:]])/},
) {
    my @warnings;
    {
        local $SIG{__WARN__} = sub { push @warnings, @_ };
        eval $source;
    }
    is($@, '', "$source compiles as literal class text");
    is_deeply(\@warnings, [], "$source compiles without POSIX diagnostics");
}
