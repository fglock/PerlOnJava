use strict;
use warnings;
use feature 'state';
use File::Temp qw(tempfile);
use Test::More;

{
    package Issue1475::Parser;

    sub new {
        my ($class, $header) = @_;
        return bless { header => $header }, $class;
    }

    sub parse {
        my ($self, $line) = @_;
        return $self->{header} . ':' . $line;
    }
}

my ($fh, $filename) = tempfile();
print {$fh} "header\nrow one\nrow two\n" or die "write $filename: $!";
close $fh or die "close $filename: $!";

open my $input, '<', $filename or die "open $filename: $!";
my @rows;

# Keep this at file scope, matching DataExtract::FixedWidth's upstream tests:
# the persistent parser is declared directly inside a file-reading loop.
while (my $line = <$input>) {
    state $parser;

    if ($. == 1) {
        $parser = Issue1475::Parser->new($line);
    }
    else {
        chomp $line;
        push @rows, $parser->parse($line);
    }
}

close $input or die "close $filename: $!";
is_deeply(\@rows, ["header\n:row one", "header\n:row two"],
    'state parser initialized on the first input line survives later reads');

unlink $filename;
done_testing;
