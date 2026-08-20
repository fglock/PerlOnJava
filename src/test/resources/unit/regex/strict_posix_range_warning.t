use strict;
use warnings;
use Test::More tests => 8;

sub compile_normal {
    my ($source) = @_;
    my @warnings;
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    eval $source;
    return ($@, @warnings);
}

my ($error, @warnings) = compile_normal(q{qr/[a-[:digit:]]/});
is($error, '', 'literal to POSIX false range is normally nonfatal');
like($warnings[0], qr/^False \[\] range "a-\[:digit:\]"/,
    'literal to POSIX false range warns');

($error, @warnings) = compile_normal(q{qr/[[:digit:]-b]/});
is($error, '', 'POSIX to literal false range is normally nonfatal');
like($warnings[0], qr/^False \[\] range "\[:digit:\]-"/,
    'POSIX to literal false range warns');

{
    use re 'strict';
    local $SIG{__WARN__} = sub {};
    eval q{qr/[a-[:digit:]]/};
    $error = $@;
}
like($error, qr/^False \[\] range "a-\[:digit:\]"/,
    're strict promotes a literal to POSIX false range');

{
    use re 'strict';
    @warnings = ();
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    eval q{qr/[A-a]/};
}
is($@, '', 'strict mixed ASCII range remains nonfatal');
like($warnings[0], qr/^Ranges of ASCII printables should be some subset/,
    'strict ordinary class receives the ASCII range warning');

{
    use re 'strict';
    @warnings = ();
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    eval q{qr/[:-\x3A]/};
}
like($warnings[0], qr/^":-\\x3A" is more clearly written simply as ":"/,
    'strict ordinary class canonicalizes equal endpoint spellings');
