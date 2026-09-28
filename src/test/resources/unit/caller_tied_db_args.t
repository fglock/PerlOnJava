use strict;
use warnings;
use Test::More tests => 4;

{
    package CallerTiedArgs;
    sub TIEARRAY { bless [], shift }
    sub EXTEND { }
    sub CLEAR { }
    sub FETCH { $_[0][$_[1]] }
    sub STORE { $_[0][$_[1]] = $_[2] }
}

{
    package DB;
    our @args;
    tie @args, 'CallerTiedArgs';
    eval { sub { () = caller 0 }->(1, 2, 3) };
    ::like($@, qr/^Cannot set tied \@DB::args at /,
        'caller rejects tied debugger arguments');
    ::ok(tied(@args), 'rejection preserves the tie');
    untie @args;
    sub { () = caller 0 }->(4, 5);
    ::is(join(',', @args), '4,5', 'caller works after untie');
    sub { () = caller 0 }->();
    ::is(scalar @args, 0, 'an empty invocation clears debugger arguments');
}
