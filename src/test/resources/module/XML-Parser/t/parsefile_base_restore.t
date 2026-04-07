use Test::More tests => 3;
use XML::Parser;

# GH#101: parsefile() permanently set $self->{Base} to the filename,
# polluting external entity resolution on subsequent parse() calls
# when reusing the same XML::Parser object.

my $parser = XML::Parser->new( ErrorContext => 2 );

# Before any parsefile(), Base should be undef
is( $parser->{Base}, undef, 'Base is undef before parsefile' );

$parser->parsefile('samples/REC-xml-19980210.xml');

# After parsefile() returns, Base should be restored to its original value
is( $parser->{Base}, undef, 'Base is restored to undef after parsefile' );

# Test that a pre-existing Base is also preserved
$parser->{Base} = '/some/custom/base';
$parser->parsefile('samples/REC-xml-19980210.xml');
is( $parser->{Base}, '/some/custom/base',
    'Base is restored to pre-existing value after parsefile' );
