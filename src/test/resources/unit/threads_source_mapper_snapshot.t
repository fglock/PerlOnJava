use strict;
use warnings;
use threads;
use Test::More;

{
    package Local::ThreadCallerProbe;
    sub caller_package { return scalar caller }
}

sub call_from_nested_package {
    package Local::ThreadCallerTarget;
    return Local::ThreadCallerProbe->caller_package();
}

is(threads->create(sub { call_from_nested_package() })->join,
    'Local::ThreadCallerTarget',
    'ithread caller retains the lexical call-site package');

sub register_overload_and_match {
    my @refs;
    my $qr = qr//;
    {
        package Local::ThreadRuntimeOverload;
        require overload;
        overload->import(
            q{""} => sub { ${$_[0]} },
            q{.} => sub {
                push @refs, ref($_[1]) || '';
                bless \("${$_[0]}$_[1]"), __PACKAGE__;
            },
        );
    }

    my $text = 'foo';
    my $object = bless \$text, 'Local::ThreadRuntimeOverload';
    local $_ = 'foo';
    /$object$qr/;
    return join('|',
        overload::Overloaded($object) ? 1 : 0,
        overload::Method($object, q{.}) ? 1 : 0,
        @refs);
}

is(threads->create(sub { register_overload_and_match() })->join,
    '1|1||Regexp',
    'runtime overload import in an ithread preserves qr operand identity');

my $parent_text = 'parent';
my $parent_object = bless \$parent_text, 'Local::ThreadRuntimeOverload';
ok(!overload::Overloaded($parent_object),
    'child runtime overload registration remains isolated from its parent');

is(register_overload_and_match(), '1|1||Regexp',
    'runtime overload import remains correct in the parent runtime');

done_testing;
