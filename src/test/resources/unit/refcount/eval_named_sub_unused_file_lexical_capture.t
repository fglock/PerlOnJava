use strict;
use warnings;
use Test::More tests => 1;
use Scalar::Util qw(weaken);

my $schema = bless {}, 'Issue2043::Schema';
my $weak_schema = $schema;
weaken($weak_schema);

# A package sub defined in this file cannot close over file lexicals. The
# eval source is literal and references no lexical, so it must not keep the
# unrelated schema alive until global destruction.
sub compile_unrelated_eval_sub {
    my $code = eval 'sub { 1 }';
    die $@ if $@;
    return $code;
}

END {
    ok(!defined($weak_schema), 'unused file lexical is released before END blocks');
}
