use strict;
use warnings;

BEGIN {
    my $root = $ENV{DBIC_CLASS_TEST_ROOT};
    if (!defined($root) || !-d "$root/t/lib" || !-d "$root/lib") {
        print "1..0 # SKIP set DBIC_CLASS_TEST_ROOT to a DBIx::Class checkout\n";
        exit 0;
    }
    $ENV{DBICTEST_NO_MAKEFILE_VERIFICATION} ||= 1;
    $ENV{AUTOMATED_TESTING} ||= 1;
    unshift @INC, "$root/t/lib", "$root/lib";
    require DBICTest;
}

use Test::More;
use B::Deparse;

my $schema = DBICTest->init_schema(no_deploy => 1);
my $inflate_map = [qw/single_track.cd.artist.name year/];
my ($parser_source) = ($schema->source('CD')->_mk_row_parser({
    inflate_map => $inflate_map,
}))[0];

# Keep the deparser in a lexical captured by a named sub, as rowparser_internals.t
# does while it compares generated parser source.
my $deparser;
sub deparse_parser_source {
    my ($source) = @_;
    $deparser ||= B::Deparse->new;
    my $parser = eval "sub { $source }";
    return $deparser->coderef2text($parser);
}

my $deparsed = deparse_parser_source($parser_source);
ok(length($deparsed), 'generated DBIx row parser can be deparsed');

done_testing();
