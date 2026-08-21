use strict;
use warnings;
no warnings 'experimental::regex_sets';
use Test::More tests => 8;

for my $source (
    q{qr/[[:digit]]/},
    q{qr/[[:DIGIT]]/},
    q{qr/[[^word]/},
) {
    my @warnings;
    {
        local $SIG{__WARN__} = sub { push @warnings, @_ };
        eval $source;
    }
    is($@, '', "$source compiles as literal class text");
    ok(@warnings, "$source reports its malformed POSIX-like spelling");
}

my @extended_warnings;
{
    local $SIG{__WARN__} = sub { push @extended_warnings, @_ };
    eval q{qr/(?[[:word]])/};
}
is($@, '', 'direct extended-class spelling compiles as literal class text');
ok(@extended_warnings, 'direct extended-class literal reports the missing colon');
