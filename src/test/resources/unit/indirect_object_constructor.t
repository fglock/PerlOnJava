#!/usr/bin/env perl
use strict;
use warnings;
use IO::File;
use Test::More;

{
    package IndirectObjectWithNew;
    sub new($$;$) { die "direct new should not be called" }
    sub make_io_file {
        my $fh = new IO::File;
        return ref($fh);
    }
}

is(
    IndirectObjectWithNew::make_io_file(),
    'IO::File',
    'new IO::File parses as an indirect constructor inside a package with new()'
);

BEGIN { $INC{'IndirectObject/ReqCtor.pm'} = __FILE__ }

{
    package IndirectObjectWithRequire;
    sub new($$;$) { die "direct new should not be called" }
    sub make_required {
        require IndirectObject::ReqCtor;
        my $obj = new IndirectObject::ReqCtor;
        return ref($obj);
    }
}

{
    package IndirectObject::ReqCtor;
    sub new { bless {}, shift }
}

is(
    IndirectObjectWithRequire::make_required(),
    'IndirectObject::ReqCtor',
    'require Foo::Bar marks the package for following indirect constructor syntax'
);

done_testing;
