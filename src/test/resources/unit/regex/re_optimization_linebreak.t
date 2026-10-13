use strict;
use warnings;
use re ();
use Test::More tests => 6;

sub is_linebreak_start_class {
    my ($class) = @_;
    return 1 if defined($class) && $class eq 'LNBREAK';
    # Perl 5.45 reports the same set as an ANYOF descriptor; PerlOnJava
    # exposes the dedicated optimizer class name used by perl5_t/t/re/opt.t.
    return defined($class)
        && $class =~ /^ANYOF\[/
        && $class =~ /\\n/
        && $class =~ /\\r/
        && $class =~ /2028-2029/;
}

my $bare = re::optimization(qr/\R/);
ok(is_linebreak_start_class($bare->{stclass}),
    'line break has a line-break start class');

my $with_literal = re::optimization(qr/\Rfoo/);
ok(is_linebreak_start_class($with_literal->{stclass}),
    'line break remains the start class before a floating literal');
is($with_literal->{floating}, 'foo',
    'literal after line break remains a floating check');

my $with_plus = re::optimization(qr/\R+foo/);
ok(is_linebreak_start_class($with_plus->{stclass}),
    'repeated line break remains the start class');
is($with_plus->{floating}, 'foo',
    'literal after repeated line break remains a floating check');
ok($with_plus->{skip}
        || (defined($with_plus->{stclass})
            && $with_plus->{stclass} =~ /^ANYOF\[/),
    'repeated line break reports a usable start-class optimization');
