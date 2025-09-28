#!/usr/bin/env perl
use strict;
use warnings;
use SparkAdapter;  # Our hypothetical adapter module

# Initialize Spark context through the adapter
my $spark = SparkAdapter->new(
    app_name => "PerlSpark Analytics",
    master => "local[*]",  # or "spark://cluster:7077" for cluster mode
    config => {
        "spark.sql.adaptive.enabled" => "true",
        "spark.serializer" => "org.apache.spark.serializer.KryoSerializer"
    }
);

# Example 1: Basic data processing pipeline
print "=== Processing Sales Data ===\n";

# Load data - adapter handles DataFrame creation
my $sales_df = $spark->read_csv("sales_data.csv", {
    header => 1,
    infer_schema => 1
});

print "Total records: " . $sales_df->count() . "\n";
$sales_df->show(5);

# Filter and transform data using Perl syntax
my $high_value_sales = $sales_df
    ->filter(sub { $_->{amount} > 1000 })  # Perl closure passed to Spark
    ->select(['customer_id', 'amount', 'product'])
    ->with_column('tax', sub { $_->{amount} * 0.08 });  # Add calculated column

print "\nHigh value sales with tax:\n";
$high_value_sales->show(10);

# Example 2: Aggregations with Perl-style syntax
my $customer_summary = $sales_df
    ->group_by('customer_id')
    ->agg({
        'amount' => ['sum', 'avg', 'count'],
        'product' => 'collect_list'
    })
    ->with_column('avg_rounded', sub { 
        my $row = shift;
        return sprintf("%.2f", $row->{avg_amount});
    });

print "\n=== Customer Summary ===\n";
$customer_summary->show();

# Example 3: Join operations
my $customers_df = $spark->read_json("customers.json");

my $enriched_data = $sales_df
    ->join($customers_df, 'customer_id', 'left')
    ->select([
        'customer_id', 'customer_name', 'region', 
        'amount', 'product', 'sale_date'
    ]);

# Example 4: Complex transformations with Perl logic
my $regional_analysis = $enriched_data
    ->group_by('region')
    ->agg_with_perl(sub {
        my $rows = shift;  # Array ref of all rows in group
        
        # Complex Perl logic for aggregation
        my %products;
        my $total_amount = 0;
        my $customer_count = 0;
        my %customers_seen;
        
        for my $row (@$rows) {
            $products{$row->{product}}++;
            $total_amount += $row->{amount};
            $customers_seen{$row->{customer_id}} = 1;
        }
        
        $customer_count = keys %customers_seen;
        my $top_product = (sort { $products{$b} <=> $products{$a} } keys %products)[0];
        
        return {
            region => $rows->[0]->{region},
            total_sales => $total_amount,
            unique_customers => $customer_count,
            top_product => $top_product,
            avg_per_customer => $customer_count > 0 ? $total_amount / $customer_count : 0
        };
    });

print "\n=== Regional Analysis ===\n";
$regional_analysis->show();

# Example 5: RDD-style operations with Perl
print "\n=== RDD Operations ===\n";

# Convert DataFrame to RDD for more flexible processing
my $sales_rdd = $sales_df->rdd();

# Map-reduce operations with Perl
my $product_stats = $sales_rdd
    ->map(sub {
        my $row = shift;
        return [$row->{product}, $row->{amount}];
    })
    ->reduce_by_key(sub {
        my ($a, $b) = @_;
        return $a + $b;  # Sum amounts by product
    })
    ->map(sub {
        my ($product, $total) = @$_;
        return {
            product => $product,
            total_sales => $total,
            category => classify_product($product)  # Custom Perl function
        };
    });

# Convert back to DataFrame and show results
my $product_df = $spark->create_dataframe($product_stats);
print "Product totals:\n";
$product_df->order_by('total_sales', 'desc')->show();

# Example 6: Machine Learning integration
print "\n=== Simple ML Pipeline ===\n";

# Prepare features using Perl logic
my $ml_data = $enriched_data
    ->with_column('features', sub {
        my $row = shift;
        # Create feature vector using Perl
        return [
            $row->{amount},
            length($row->{product}),
            $row->{region} eq 'North' ? 1 : 0,
            $row->{region} eq 'South' ? 1 : 0,
            $row->{region} eq 'East' ? 1 : 0,
            $row->{region} eq 'West' ? 1 : 0,
        ];
    })
    ->select(['customer_id', 'features']);

# Use Spark ML through adapter
my $kmeans = $spark->ml->kmeans(k => 3);
my $model = $kmeans->fit($ml_data);
my $predictions = $model->transform($ml_data);

print "Customer clusters:\n";
$predictions->show();

# Example 7: Streaming (if supported)
print "\n=== Streaming Example ===\n";

my $stream = $spark->read_stream()
    ->format("socket")
    ->option("host", "localhost")
    ->option("port", 9999)
    ->load();

# Process streaming data with Perl
my $word_counts = $stream
    ->flat_map(sub {
        my $line = shift->{value};
        return [split /\s+/, lc($line)];  # Perl regex split
    })
    ->group_by('value')
    ->count()
    ->with_column('processed_at', sub { time() });

# Start streaming query
my $query = $word_counts
    ->write_stream()
    ->output_mode("complete")
    ->format("console")
    ->trigger("processingTime", "10 seconds")
    ->start();

# Save results
print "\n=== Saving Results ===\n";

# Various output formats
$regional_analysis->write()
    ->mode("overwrite")
    ->option("header", "true")
    ->csv("output/regional_analysis");

$product_df->write()
    ->mode("overwrite")
    ->parquet("output/product_stats.parquet");

# Custom Perl function used in processing
sub classify_product {
    my $product = shift;
    
    return "Electronics" if $product =~ /phone|laptop|tablet/i;
    return "Clothing" if $product =~ /shirt|pants|dress|shoes/i;
    return "Home" if $product =~ /furniture|appliance|decor/i;
    return "Other";
}

print "\nProcessing complete!\n";

# Clean up
$spark->stop();

