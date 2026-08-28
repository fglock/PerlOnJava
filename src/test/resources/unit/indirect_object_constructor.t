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

# A fully-qualified class used only in platform-specific code need not be
# loaded for Perl to parse an indirect constructor followed by a method call.
# Argv::Win32Utils uses this exact form on non-Windows platforms.
sub compile_only_unknown_qualified_constructor {
    my %pid_tree = new Win32::Process::Info->Subprocesses(1);
}

pass('new Unknown::Qualified->method(...) parses without loading the class');

# Config.pm has long supported this historical parenthesized hash lookup.
# NetAddr::IP's pure-Perl Util_IS.pm generator uses it in code guarded by its
# -noxs option, so the source must still parse even when that branch is idle.
use Config;
{
    no strict 'vars';
    if (0) {
        system $Config(sh), 'true';
    }
    pass('historical $Config(sh) command argument parses');
}

done_testing;
