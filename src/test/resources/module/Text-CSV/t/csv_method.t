use strict;
use warnings;
use File::Spec;
use Test::More tests => 5;

BEGIN {
    $ENV{PERL_TEXT_CSV} = $ENV{TEST_PERL_TEXT_CSV} || 0;
    require_ok "Text::CSV";
    plan skip_all => "Cannot load Text::CSV" if $@;
    }

{
    my $file = prepare('1,2,3');
    my $csv = Text::CSV->new ();
    ok my $aoa = eval { $csv->csv (in => $file) };
    is_deeply($aoa, [[1,2,3]]) or note explain $aoa;
    unlink $file;
}

{
    my $file = prepare('col1;col2;col3','1;2;3');
    my $csv = Text::CSV->new ({ sep_char => ";" });
    ok my $aoh = eval { $csv->csv (in => $file, bom => 1) };
    is_deeply($aoh, [{col1 => 1, col2 => 2, col3 => 3}]) or note explain $aoh;
    unlink $file;
}

sub prepare {
    my @lines = @_;
    my $file = File::Spec->catfile(File::Spec->tmpdir, "file.csv");
    open my $fh, '>', $file;
    print $fh "$_\n" for @lines;
    close $fh;
    $file;
}
