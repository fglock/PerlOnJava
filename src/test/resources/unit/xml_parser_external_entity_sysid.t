use strict;
use warnings;
use Test::More;
use File::Temp qw(tempfile);
use XML::Parser;

my ($fh, $filename) = tempfile(UNLINK => 1);
print {$fh} "external";
close $fh;

for my $case (
    [ "file://$filename", "file://$filename", "file URI system ID is preserved" ],
    [ $filename,          $filename,          "absolute path system ID is preserved" ],
    [ "rel.ent",         "rel.ent",         "relative system ID is preserved" ],
) {
    my ($input, $expected, $name) = @{$case};
    my $seen;
    my $xml = qq(<!DOCTYPE foo [ <!ENTITY xxe SYSTEM "$input" >]><foo>&xxe;</foo>);
    my $parser = XML::Parser->new(
        Handlers => {
            ExternEnt => sub {
                my ($xp, $base, $sysid) = @_;
                $seen = $sysid;
                return "";
            },
        },
    );
    $parser->parse($xml);
    is($seen, $expected, $name);
}

unlink($filename) if -f $filename;

done_testing;
